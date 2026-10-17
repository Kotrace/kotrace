package dev.kotrace.room

import androidx.room.RoomDatabase
import dev.kotrace.adapter.FaultPhase
import dev.kotrace.bridge.runIsolated
import dev.kotrace.currentThreadSpan
import dev.kotrace.event.log
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * A Room [RoomDatabase.QueryCallback] that logs each executed SQL statement onto the kotrace span the query runs
 * under, then forwards the query to the app's own callback, if any. Install it with [tracing] rather than by hand.
 *
 * **What it logs.** One log per statement, `SQL <statement>`, on `currentThreadSpan()`. With no span on the thread,
 * nothing is logged. Bind arguments are never read, so their values never reach a log.
 *
 * **Why it must run inline.** Room runs a suspend query under the caller's coroutine, and kotrace mirrors that
 * coroutine's span onto Room's thread for the call. `currentThreadSpan()` sees the span only on that thread and only
 * during the call, so this callback must run on the query thread itself: [tracing] installs it with a direct
 * executor. Set by hand on any other executor, it finds no span and logs nothing.
 *
 * **Sensitive by default.** Each log is marked `sensitive`: a `@RawQuery` built by concatenation can put user values
 * into the SQL text itself. A sink receives it only if its policy opts in to sensitive events
 * ([dev.kotrace.adapter.TracePolicy.acceptsSensitive]).
 *
 * **[attributes]** are the caller's tags on every log, such as a severity under its own key, snapshotted at
 * construction (ADR-028). SQL is high-volume, so tag it at a level the policies drop by default.
 *
 * **[delegate]** is the app's own callback. Room holds only one query callback and this one takes it, so pass the
 * app's here to keep it: it receives every query, on the query thread, after the log, even if logging failed.
 * [tracing] can move it onto the app's executor instead.
 *
 * **Faults.** A fault while logging is contained and reported to the `FaultHook` as
 * [dev.kotrace.adapter.FaultPhase.DATABASE] (ADR-035): Room runs the callback in its own scope, where a throw can crash
 * the app and silence every later query. Two failures propagate instead, after [delegate] has run: a JVM-fatal error,
 * and the strict-not-installed failure (ADR-011), which [dev.kotrace.Kotrace.strictWhenNotInstalled] arms only in a
 * debug build, to make a missing install loud. A throw from [delegate] is the app's own and propagates, as it would if
 * the app had set its callback on Room directly; it wins over a failure from logging.
 */
class TracingQueryCallback(
    attributes: Map<String, String> = emptyMap(),
    private val delegate: RoomDatabase.QueryCallback? = null,
) : RoomDatabase.QueryCallback {
    /** Snapshotted (ADR-028): a caller mutating its map later changes no log. */
    private val attributes: Map<String, String> = attributes.toMap()

    override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
        // The app's callback runs whatever logging did, so a missing install never costs it a query (ADR-035).
        try {
            runIsolated(FaultPhase.DATABASE, "database.query.log", recover = {}) {
                currentThreadSpan()?.log(attributes, sensitive = true) { "SQL ${sqlQuery.trim()}" }
            }
        } finally {
            delegate?.onQuery(sqlQuery, bindArgs)
        }
    }
}


/** Runs the callback inline on the query thread, where `currentThreadSpan()` still resolves. */
private val DirectExecutor = Executor { it.run() }

/**
 * Logs every SQL statement this database executes onto the kotrace span the query runs under, tagged with
 * [attributes]: one `sensitive` log per statement, its text only, never the bound values ([TracingQueryCallback]).
 *
 * ```kotlin
 * Room.databaseBuilder(context, AppDatabase::class.java, "app.db")
 *     .tracing(mapOf("level" to "DEBUG"), delegate = appCallback, delegateExecutor = appExecutor)
 *     .build()
 * ```
 *
 * **It takes Room's query callback.** Room holds only one, and this sets it, with an executor that runs it inline on
 * the query thread, the only place the span is visible. So:
 * - a callback set earlier with `setQueryCallback` is replaced. Pass it as [delegate] instead: it receives every
 *   query, after the log;
 * - [delegateExecutor] runs [delegate] where `setQueryCallback(delegate, delegateExecutor)` would have, with the bind
 *   arguments copied, and a query that executor refuses is dropped, as Room drops it. Without one, [delegate] runs
 *   inline on the query thread;
 * - a `setQueryCallback` called after this replaces kotrace's callback, and nothing is logged.
 *
 * **Not with `setDriver(SQLiteDriver)`.** Room rejects every query callback then, and `build()` throws, so use this
 * only on a builder backed by `SupportSQLiteOpenHelper`, the default.
 *
 * **Not on a sink's own database.** A trace sink writing to a database built with this logs its own inserts onto
 * every traced flow; give the sink a database without it.
 */
fun <T : RoomDatabase> RoomDatabase.Builder<T>.tracing(
    attributes: Map<String, String> = emptyMap(),
    delegate: RoomDatabase.QueryCallback? = null,
    delegateExecutor: Executor? = null,
): RoomDatabase.Builder<T> {
    val forwarded = if (delegate == null || delegateExecutor == null) delegate else delegate.dispatchedTo(delegateExecutor)
    return setQueryCallback(TracingQueryCallback(attributes, forwarded), DirectExecutor)
}

/**
 * Wraps this callback so each query reaches it on [executor], as `setQueryCallback(callback, executor)` would have
 * delivered it. [tracing] needs this because Room holds one query callback: kotrace's own takes that slot and must
 * run inline on the query thread, so the app's callback, passed as the `delegate`, is forwarded from there, and this
 * restores the executor half of what Room would have done for it.
 *
 * The returned callback, run on the query thread:
 * - copies the bind arguments, because [executor] runs the task later, on another thread, and Room may reuse its list
 *   once `onQuery` returns;
 * - hands the call to [executor] and returns at once;
 * - drops the call if [executor] refuses it with a [RejectedExecutionException], as Room's own dispatch would, since
 *   a throw would land in Room's callback scope, where it can crash the app and silence every later query.
 *
 * A throw from this callback itself happens on [executor]'s thread and is left to that executor, as it would be
 * under Room.
 */
internal fun RoomDatabase.QueryCallback.dispatchedTo(executor: Executor): RoomDatabase.QueryCallback {
    val delegate = this
    return RoomDatabase.QueryCallback { sql, args ->
        val snapshot = args.toList()
        try {
            executor.execute { delegate.onQuery(sql, snapshot) }
        } catch (_: RejectedExecutionException) {
            // Dropped, like a rejected dispatch in Room's own callback scope.
        }
    }
}
