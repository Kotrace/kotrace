package dev.kotrace.room

import android.annotation.SuppressLint
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.room.RoomDatabase
import dev.kotrace.adapter.FaultPhase
import dev.kotrace.bridge.NonSuspendTracingBridge
import dev.kotrace.bridge.TraceHandoff
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
 * **Why it must run inline.** Room runs a query on its own thread, and `currentThreadSpan()` sees the caller's span
 * there only while the query runs, so this callback must run on the query thread itself: [tracing] installs it with a
 * direct executor. Set by hand on any other executor, it finds no span and logs nothing. How the span reaches Room's
 * thread is [tracing]'s query executor.
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
 * **It takes Room's query executor too.** A suspend read does not always run under the caller's coroutine: Room 2.6
 * launches it in its own scope, which carries no span to Room's thread. So this sets a query executor that carries
 * the trace context of the thread submitting each task to the thread running it ([TraceHandoff.withCapturedContext]),
 * over [queryExecutor], else Room's own default. So:
 * - pass the executor you would set with `setQueryExecutor` as [queryExecutor] instead; a `setQueryExecutor` called
 *   before this is replaced, and one called after it replaces kotrace's, so suspend reads on Room 2.6 log nothing;
 * - Room runs transactions on the query executor when no transaction executor is set, so they are carried too. An app
 *   that sets only `setTransactionExecutor`, and so ran its queries there, passes that executor as [queryExecutor]
 *   to keep them there;
 * - a task Room submits from a traced query thread, such as its invalidation refresh after a write, carries the
 *   same span, and its SQL is logged there.
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
@SuppressLint("RestrictedApi")
fun <T : RoomDatabase> RoomDatabase.Builder<T>.tracing(
    attributes: Map<String, String> = emptyMap(),
    delegate: RoomDatabase.QueryCallback? = null,
    delegateExecutor: Executor? = null,
    queryExecutor: Executor? = null,
): RoomDatabase.Builder<T> {
    val forwarded = if (delegate == null || delegateExecutor == null) delegate else delegate.dispatchedTo(delegateExecutor)
    // Room's own default query executor, a restricted API of the Room library group, read here only.
    return setQueryCallback(TracingQueryCallback(attributes, forwarded), DirectExecutor)
        .setQueryExecutor((queryExecutor ?: ArchTaskExecutor.getIOThreadExecutor()).carryingTrace())
}

/** [tracing] without its query executor parameter, kept for code compiled against an earlier kotrace. */
@Deprecated("Replaced by the overload taking queryExecutor.", level = DeprecationLevel.HIDDEN)
fun <T : RoomDatabase> RoomDatabase.Builder<T>.tracing(
    attributes: Map<String, String> = emptyMap(),
    delegate: RoomDatabase.QueryCallback? = null,
    delegateExecutor: Executor? = null,
): RoomDatabase.Builder<T> = tracing(attributes, delegate, delegateExecutor, queryExecutor = null)

/**
 * Wraps this executor so each task runs with the trace context of the thread that submitted it
 * ([TraceHandoff.withCapturedContext]): the span a query runs under reaches Room's thread even when Room launched the
 * query outside the caller's coroutine. A task submitted with nothing to carry runs unwrapped.
 *
 * The capture runs on the submitting thread, inside Room's dispatch, so a fault in it is contained and reported to
 * the `FaultHook` as [dev.kotrace.adapter.FaultPhase.DATABASE] (ADR-035), and the task runs unwrapped. Only a JVM-fatal
 * error and the strict-not-installed failure (ADR-011) propagate, failing the dispatch.
 */
@OptIn(NonSuspendTracingBridge::class)
internal fun Executor.carryingTrace(): Executor {
    val delegate = this
    return Executor { task ->
        val handoff = runIsolated(FaultPhase.DATABASE, "database.query.capture", recover = { null }) {
            TraceHandoff.captureOrNull()
        }
        delegate.execute(if (handoff == null) task else Runnable { handoff.withCapturedContext { task.run() } })
    }
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
 * under Room. On an executor that runs it inline, that throw propagates, even a [RejectedExecutionException]: only a
 * task that never started counts as refused.
 */
internal fun RoomDatabase.QueryCallback.dispatchedTo(executor: Executor): RoomDatabase.QueryCallback {
    val delegate = this
    return RoomDatabase.QueryCallback { sql, args ->
        val snapshot = args.toList()
        var started = false
        try {
            executor.execute {
                started = true
                delegate.onQuery(sql, snapshot)
            }
        } catch (rejected: RejectedExecutionException) {
            // Dropped, like a rejected dispatch in Room's own callback scope; once started, the throw is the app's.
            if (started) throw rejected
        }
    }
}
