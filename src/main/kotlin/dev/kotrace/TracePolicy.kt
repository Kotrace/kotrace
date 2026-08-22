package dev.kotrace

import dev.kotrace.event.ExceptionEvent
import dev.kotrace.event.LogEvent
import dev.kotrace.event.NamedEvent
import dev.kotrace.event.SpanEvent

/**
 * A consumer's fan-out **decisions**, carried by every [TraceAdapter] and separate from where the adapter
 * routes a record. Three gates — [acceptsSpan], [acceptsEvent], [acceptsSensitive] — applied identically
 * at live emit and report fan-out (see [accepts]). Every gate defaults open-but-safe: spans and events
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
}

/**
 * Combines the gates for one [event] on one [span] — the single predicate used at both live emit and
 * report fan-out. [TracePolicy.acceptsSpan] and [TracePolicy.acceptsSensitive] apply only to the attributed
 * kinds; an [dev.kotrace.event.ExceptionEvent] is gated by [TracePolicy.acceptsEvent] alone (no span/sensitive
 * filter — a crash is not a layer breadcrumb).
 */
internal fun TracePolicy.accepts(span: Span, event: SpanEvent): Boolean = when (event) {
    is LogEvent -> acceptsSpan(span) && acceptsEvent(event) && (!event.sensitive || acceptsSensitive)
    is NamedEvent -> acceptsSpan(span) && acceptsEvent(event)
    is ExceptionEvent -> acceptsEvent(event)
}
