package dev.kotrace.room

import androidx.room.RoomDatabase
import dev.kotrace.currentThreadSpan
import dev.kotrace.event.log
import dev.kotrace.isJvmFatal
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * A Room [RoomDatabase.QueryCallback] that logs each executed statement onto the active kotrace span.
 *
 * Room runs a suspend query under the caller's coroutine, and kotrace mirrors the span onto Room's thread for the
 * call, so `currentThreadSpan()` resolves it. That holds only while the callback runs inline, which is why
 * [tracing] installs it with a direct executor.
 *
 * The SQL is logged **sensitive**: bind arguments are never read, but a `@RawQuery` built by concatenation can
 * inline user values into the text, and a report sink drops sensitive events unless its policy opts in. The caller
 * supplies the log [attributes], such as a severity under its own key; SQL is high-volume, so tag it at a level
 * the capture policy drops by default.
 *
 * Room holds **one** query callback, so this replaces any the app had. Pass that one as [delegate] to keep it: it
 * receives every query after the span log, even if logging fails.
 *
 * A telemetry fault while logging (a strict-uninstalled config, ADR-011) is contained: Room runs the callback in
 * its own scope, where a throw would crash the app and silence every later query. A JVM-fatal error propagates.
 */
class TracingQueryCallback(
    attributes: Map<String, String> = emptyMap(),
    private val delegate: RoomDatabase.QueryCallback? = null,
) : RoomDatabase.QueryCallback {
    /** Snapshotted (ADR-028): a caller mutating its map later changes no log. */
    private val attributes: Map<String, String> = attributes.toMap()

    override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
        try {
            currentThreadSpan()?.log(attributes, sensitive = true) { "SQL ${sqlQuery.trim()}" }
        } catch (t: Throwable) {
            if (t.isJvmFatal()) throw t
        }
        delegate?.onQuery(sqlQuery, bindArgs)
    }
}


/** Runs the callback inline on the query thread, where `currentThreadSpan()` still resolves. */
private val DirectExecutor = Executor { it.run() }

/**
 * Installs span logging on this builder: every query then logs its SQL to the active span, tagged with
 * [attributes].
 *
 * Room keeps a single query callback, so this **replaces** one set with `setQueryCallback`. To keep yours, pass it
 * as [delegate]. It runs on [delegateExecutor] when given (a query that executor refuses is dropped, as Room
 * would), else inline on the query thread.
 *
 * **Not supported with `setDriver(SQLiteDriver)`:** Room's `build()` rejects any query callback then, so use this
 * only on a `SupportSQLiteOpenHelper`-backed builder (the default).
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
 * This callback run on [executor] with a snapshot of the bind arguments, as Room does with an app's executor. A
 * refused task drops that one call instead of throwing into Room's callback scope.
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
