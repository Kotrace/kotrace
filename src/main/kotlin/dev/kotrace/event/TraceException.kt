package dev.kotrace.event

import dev.kotrace.Span
import dev.kotrace.currentThreadConfig

/**
 * The birthplace throwable as a timeline event — the crash cause, ordered among the span's log lines.
 * Carries the raw [throwable] (not stringified attributes): a crash reporter needs the live object for
 * symbolication and grouping. It has no attributes, so a [dev.kotrace.TracePolicy] filtering on them leaves it alone;
 * only a policy that explicitly inspects an [ExceptionEvent] in [dev.kotrace.TracePolicy.acceptsEvent] can drop it.
 */
class ExceptionEvent(
    val throwable: Throwable,
    override val atNanos: Long,
) : SpanEvent

/**
 * The crash record — identity + the birthplace [throwable]. Synthesised from an [ExceptionEvent] at
 * fan-out; it carries no attributes, so a policy filtering on them keeps it by default — only a policy that
 * covers exceptions ([dev.kotrace.TracePolicy.acceptsEvent]) can drop the crash cause.
 *
 * Two-sink PII split (see [dev.kotrace.Span]'s invariant): the raw [throwable] — the one field allowed to
 * carry user data — must go **only** to a crash reporter, never to the general [toJson] log path (which
 * renders the class name only). An adapter routing it there **must stamp [traceId] as a searchable key on
 * the crash report** (e.g. Crashlytics `setCustomKey("trace_id", traceId)` before `recordException`);
 * `trace_id` is the sole join key between the two sinks. Without it, operations can see *that* a trace
 * failed in the log store but cannot pull its detail from the crash reporter.
 */
data class ExceptionRecord(
    override val traceId: String,
    override val spanId: String,
    override val parentId: String?,
    override val operation: String,
    override val atNanos: Long,
    override val info: Map<String, String>,
    val throwable: Throwable,
) : TraceRecord

/**
 * Records the birthplace [cause] as an [ExceptionEvent] on this span's timeline, and — like [log]/[addNamed]
 * — offers it to each live adapter as it happens, so a debug live watch sees a crash the moment it is thrown
 * rather than only at report fan-out ([dev.kotrace.reportTrace]).
 *
 * Like every event verb it appends unconditionally (there is no capture gate — ADR-002); a layer-filtered
 * trace must never lose its crash. A live adapter then sees it iff its policy [dev.kotrace.accepts] the
 * [ExceptionEvent], which by default keeps it (a crash carries no `acceptsSpan`/sensitive gate, only the
 * opt-in [dev.kotrace.TracePolicy.acceptsEvent]). The report path is unchanged: the appended event still fans out
 * once, at the birthplace ([dev.kotrace.reportTrace]).
 *
 * Note the throwable climbs the tree: it is re-recorded on every enclosing span as it rethrows
 * ([dev.kotrace.span]), so a live watch sees one line per ancestor — deepest (birthplace) first. Report
 * dedups that to the single birthplace record; live deliberately does not, showing the propagation trail.
 */
fun Span.addException(cause: Throwable) {
    emit(ExceptionEvent(cause, System.nanoTime()), currentThreadConfig())
}

/** The birthplace throwable recorded on this span, if any — the first [ExceptionEvent] on its timeline. */
val Span.exception: Throwable?
    get() = events.firstNotNullOfOrNull { (it as? ExceptionEvent)?.throwable }