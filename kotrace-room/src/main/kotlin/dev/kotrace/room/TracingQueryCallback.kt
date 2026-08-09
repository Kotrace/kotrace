package dev.kotrace.room

import androidx.room.RoomDatabase
import dev.kotrace.LogLevel
import dev.kotrace.currentSpan
import dev.kotrace.log
import java.util.concurrent.Executor

/**
 * A Room [RoomDatabase.QueryCallback] that logs each executed statement onto the active kotrace span.
 *
 * It resolves the span through kotrace's `currentSpan()` ThreadLocal — no request-tag bridge like OkHttp
 * needs — because Room runs a suspend query under the caller's coroutine via `withContext`, and kotrace's
 * `SpanContext` is a `ThreadContextElement`, so the span is mirrored onto Room's executor thread for the
 * duration of the call. Installed with a **direct executor** ([install]) so `onQuery` fires inline on
 * that thread while the mirror is live; a background executor would run it after the span is gone.
 *
 * Only the SQL text — parameterised symbols (`?` placeholders) — is logged. `bindArgs` are the bound
 * **values**, which can be user data, so they are never touched (kotrace's symbol-only rule / EH-MON-4).
 *
 * [level] defaults to DEBUG: SQL is high-volume, so it is off under the default capture set
 * (INFO+WARN+ERROR) and appears only when a build opts DEBUG in.
 */
class TracingQueryCallback(private val level: LogLevel = LogLevel.DEBUG) : RoomDatabase.QueryCallback {
    override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
        currentSpan()?.log(level) { "SQL ${sqlQuery.trim()}" }
    }
}

/** Runs the callback inline on the calling (query) thread, where `currentSpan()` still resolves. */
private val DirectExecutor = Executor { it.run() }

/**
 * Installs span-logging on this builder — the whole consumer setup. Call in the `Room.databaseBuilder`
 * chain; every query then logs its SQL to the active span.
 */
fun <T : RoomDatabase> RoomDatabase.Builder<T>.tracing(
    level: LogLevel = LogLevel.DEBUG,
): RoomDatabase.Builder<T> = setQueryCallback(TracingQueryCallback(level), DirectExecutor)
