package dev.kotrace.room

import androidx.room.RoomDatabase
import dev.kotrace.currentThreadSpan
import dev.kotrace.event.log
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * A Room [RoomDatabase.QueryCallback] that logs each executed statement onto the active kotrace span.
 *
 * It resolves the span through kotrace's `currentThreadSpan()` ThreadLocal — no request-tag bridge like OkHttp
 * needs — because Room runs a suspend query under the caller's coroutine via `withContext`, and kotrace's
 * `SpanContext` is a `ThreadContextElement`, so the span is mirrored onto Room's executor thread for the
 * duration of the call. Installed with a **direct executor** ([tracing]) so `onQuery` fires inline on
 * that thread while the mirror is live; a background executor would run it after the span is gone.
 *
 * The SQL text is logged as a **sensitive** event. `bindArgs` (the bound values) are never touched, but the
 * SQL string itself is not guaranteed to be symbol-only: a `@RawQuery` / `SimpleSQLiteQuery` built by string
 * concatenation can inline user values directly into the text. Marking it sensitive is fail-closed — the
 * report fan-out drops it unless a policy opts into sensitive, so an inlined value can never reach a broad
 * sink by accident (kotrace's symbol-only rule / EH-MON-4). A consumer whose SQL is strictly parameterised
 * can opt sensitive in for its device-only watch.
 *
 * kotrace core holds no severity taxonomy, so the caller supplies the log [attributes] (e.g. a severity
 * level under the consumer's own key). SQL is high-volume, so a consumer typically tags it at a level its
 * capture policy drops by default, opting it in only on a debug build.
 *
 * Room holds **one** query callback, so installing this one replaces any the app already had. Pass that one as
 * [delegate] to keep it: it receives every query after the span log, even if logging fails.
 */
class TracingQueryCallback(
    private val attributes: Map<String, String> = emptyMap(),
    private val delegate: RoomDatabase.QueryCallback? = null,
) : RoomDatabase.QueryCallback {
    override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
        try {
            currentThreadSpan()?.log(attributes, sensitive = true) { "SQL ${sqlQuery.trim()}" }
        } finally {
            delegate?.onQuery(sqlQuery, bindArgs)
        }
    }
}

/** Runs the callback inline on the calling (query) thread, where `currentThreadSpan()` still resolves. */
private val DirectExecutor = Executor { it.run() }

/**
 * Installs span-logging on this builder — the whole consumer setup. Call in the `Room.databaseBuilder`
 * chain; every query then logs its SQL to the active span, tagged with [attributes].
 *
 * Room keeps a single query callback, so this **replaces** one set earlier with `setQueryCallback`. To keep
 * yours, pass it as [delegate] (instead of calling `setQueryCallback` yourself). It is run on
 * [delegateExecutor] when given (a query that executor refuses is dropped, as Room would), or else inline on the
 * query thread, since the span log must run inline.
 *
 * **Not supported with `setDriver(SQLiteDriver)`.** Room rejects any query callback when a driver is configured
 * (`build()` throws "Query Callback is not supported when an SQLiteDriver is configured"), so call this only on a
 * `SupportSQLiteOpenHelper`-backed builder (the default).
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
 * This callback run on [executor], with a snapshot of each query's bind arguments — what Room does with the executor
 * an app passes to `setQueryCallback`. An executor that refuses the task (shut down, saturated) drops that one call,
 * as Room's own dispatch does, instead of throwing into Room's callback scope (see [TracingQueryCallback]).
 */
internal fun RoomDatabase.QueryCallback.dispatchedTo(executor: Executor): RoomDatabase.QueryCallback {
    val delegate = this
    return RoomDatabase.QueryCallback { sql, args ->
        val snapshot = args.toList()
        try {
            executor.execute { delegate.onQuery(sql, snapshot) }
        } catch (rejected: RejectedExecutionException) {
            // Dropped, like a rejected dispatch in Room's own callback scope.
        }
    }
}
