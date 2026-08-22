package dev.kotrace.room

import androidx.room.RoomDatabase
import dev.kotrace.currentThreadSpan
import dev.kotrace.event.log
import java.util.concurrent.Executor

/**
 * A Room [RoomDatabase.QueryCallback] that logs each executed statement onto the active kotrace span.
 *
 * It resolves the span through kotrace's `currentThreadSpan()` ThreadLocal — no request-tag bridge like OkHttp
 * needs — because Room runs a suspend query under the caller's coroutine via `withContext`, and kotrace's
 * `SpanContext` is a `ThreadContextElement`, so the span is mirrored onto Room's executor thread for the
 * duration of the call. Installed with a **direct executor** ([tracing]) so `onQuery` fires inline on
 * that thread while the mirror is live; a background executor would run it after the span is gone.
 *
 * Only the SQL text — parameterised symbols (`?` placeholders) — is logged. `bindArgs` are the bound
 * **values**, which can be user data, so they are never touched (kotrace's symbol-only rule / EH-MON-4).
 *
 * kotrace core holds no severity taxonomy, so the caller supplies the log [attributes] (e.g. a severity
 * level under the consumer's own key). SQL is high-volume, so a consumer typically tags it at a level its
 * capture policy drops by default, opting it in only on a debug build.
 */
class TracingQueryCallback(private val attributes: Map<String, String> = emptyMap()) : RoomDatabase.QueryCallback {
    override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
        currentThreadSpan()?.log(attributes) { "SQL ${sqlQuery.trim()}" }
    }
}

/** Runs the callback inline on the calling (query) thread, where `currentThreadSpan()` still resolves. */
private val DirectExecutor = Executor { it.run() }

/**
 * Installs span-logging on this builder — the whole consumer setup. Call in the `Room.databaseBuilder`
 * chain; every query then logs its SQL to the active span, tagged with [attributes].
 */
fun <T : RoomDatabase> RoomDatabase.Builder<T>.tracing(
    attributes: Map<String, String> = emptyMap(),
): RoomDatabase.Builder<T> = setQueryCallback(TracingQueryCallback(attributes), DirectExecutor)
