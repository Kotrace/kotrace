package dev.kotrace

import dev.kotrace.event.ExceptionEvent
import dev.kotrace.event.LogEvent
import dev.kotrace.event.NamedEvent
import dev.kotrace.event.SpanEvent

/**
 * A consumer's fan-out **decisions**, carried by every [TraceAdapter] and separate from where the adapter
 * routes a record. Three gates — [acceptsSpan], [acceptsEvent], [acceptsSensitive] — applied identically
 * at live emit time and report fan-out (see [accepts]). Every gate defaults open-but-safe: spans and events
 * pass, sensitive records don't, so a bare policy is a working, fail-closed policy.
 */
interface TracePolicy {
    /**
     * Does this adapter want breadcrumbs from [span]? A per-span filter, typically keyed off
     * [Span.attributes] (e.g., a "layer" the consumer stamped). kotrace never reads the attributes — the
     * meaning is entirely the override's. Not applied to an [dev.kotrace.event.ExceptionEvent] (a crash is
     * not a layer breadcrumb). Default accepts every span.
     */
    fun acceptsSpan(span: Span): Boolean = true

    /**
     * Does this adapter want [event]? A per-event filter over the umbrella [SpanEvent], so one override
     * ranks a [LogEvent]/[NamedEvent] on its attributes (e.g., a severity threshold on a `"level"` key)
     * **and** optionally covers an [ExceptionEvent]. Takes the umbrella, not a leaf, precisely so a policy
     * *can* drop a crash — most won't. kotrace holds no severity taxonomy, so ranking lives here (there is
     * no separate capture gate — ADR-002). Default accepts everything, so a crash passes unless dropped.
     */
    fun acceptsEvent(event: SpanEvent): Boolean = true

    /**
     * Does this adapter receive records a capture site marked [LogEvent.sensitive] (a captured body and
     * the like)? This is the **routing** half; [LogEvent.sensitive] is the **classification** half — the
     * event states it carries user data, the policy states whether *this* adapter may see it, so one
     * sensitive record can reach a debug sink yet be withheld from a crash reporter. Defaults `false`
     * (fail-closed): a new adapter never leaks user data by omission.
     */
    val acceptsSensitive: Boolean get() = false

    /**
     * Does this adapter receive the **PROPAGATED** copies of a climbing exception, or only the single
     * **BIRTHPLACE** per lineage (ADR-019)? Default `false` — the birthplace-only report of ADR-005, so a
     * crash reporter is never handed N duplicate crash groups. A log / trace-visualisation adapter sets `true`
     * to receive the whole marked climb (each record's [dev.kotrace.event.ExceptionRecord.origin] tells the
     * two apart). **Report-only**: the live path is per-event and already un-deduped, so this gate does not
     * apply there — it is read only by the report `viewOf`, not by [accepts].
     */
    val acceptsPropagatedException: Boolean get() = false
}

/**
 * Combines the gates for one [event] on one [span] — the single predicate used at both live emit time and
 * report fan-out. [TracePolicy.acceptsSpan] and [TracePolicy.acceptsSensitive] apply only to the attributed
 * kinds; an [dev.kotrace.event.ExceptionEvent] is gated by [TracePolicy.acceptsEvent] alone (no span/sensitive
 * filter — a crash is not a layer breadcrumb).
 */
internal fun TracePolicy.accepts(span: Span, event: SpanEvent): Boolean = when (event) {
    is LogEvent -> acceptsSpan(span) && acceptsEvent(event) && (!event.sensitive || acceptsSensitive)
    is NamedEvent -> acceptsSpan(span) && acceptsEvent(event)
    is ExceptionEvent -> acceptsEvent(event)
}

/**
 * The span-**less** predicate for a default-registry emit (ADR-010) — same gates as [accepts] minus
 * [TracePolicy.acceptsSpan], which has no meaning without a span (it keys off [Span.attributes]).
 * [TracePolicy.acceptsEvent] and (for a [LogEvent]) [TracePolicy.acceptsSensitive] still apply, so fan-out
 * stays the single filtering authority even
 * for an orphan emit.
 */
internal fun TracePolicy.accepts(event: SpanEvent): Boolean = when (event) {
    is LogEvent -> acceptsEvent(event) && (!event.sensitive || acceptsSensitive)
    is NamedEvent -> acceptsEvent(event)
    is ExceptionEvent -> acceptsEvent(event)
}
