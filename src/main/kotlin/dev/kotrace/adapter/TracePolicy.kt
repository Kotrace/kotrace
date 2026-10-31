package dev.kotrace.adapter

import dev.kotrace.Span
import dev.kotrace.TraceLink
import dev.kotrace.bridge.runIsolated
import dev.kotrace.event.ExceptionEvent
import dev.kotrace.event.ExceptionRecord
import dev.kotrace.event.LogEvent
import dev.kotrace.event.LogRecord
import dev.kotrace.event.NamedEvent
import dev.kotrace.event.NamedRecord
import dev.kotrace.event.SpanEvent
import dev.kotrace.event.TraceRecord

/**
 * An adapter's fan-out **decisions**, separate from where it routes a record: gates applied alike on the live and
 * report paths (see [accepts]). The defaults pass spans and events and withhold sensitive records and every
 * attribute, so a bare policy is a working, fail-closed one.
 */
interface TracePolicy {
    /**
     * Whether this adapter wants breadcrumbs from [span], typically keyed off [Span.traits] (a `layer`).
     * Never applied to an [ExceptionEvent]: a crash is not a layer breadcrumb.
     */
    fun acceptsSpan(span: Span): Boolean = true

    /**
     * Whether this adapter wants [event], such as a severity threshold on a log's attributes; kotrace holds no
     * severity of its own (ADR-002). It takes any [SpanEvent], so a policy *can* drop an [ExceptionEvent]; most
     * will not.
     */
    fun acceptsEvent(event: SpanEvent): Boolean = true

    /**
     * Whether this adapter receives events marked [LogEvent.sensitive]: the event says it carries user data, the
     * policy says whether this adapter may see it. `false` by default, so a new adapter never leaks by omission.
     */
    val acceptsSensitive: Boolean get() = false

    /**
     * Whether the report also hands this adapter the **PROPAGATED** copies of a climbing exception, not only its
     * **BIRTHPLACE** (ADR-019). `false` by default, so a crash reporter gets one group per failure; a trace viewer
     * sets `true` and reads [dev.kotrace.event.ExceptionRecord.origin]. Report only: the live path is per-event.
     */
    val acceptsPropagatedException: Boolean get() = false

    /**
     * Whether the report hands this adapter [dev.kotrace.event.ExceptionOrigin.EXPLICIT] records from
     * [dev.kotrace.event.addException] (ADR-040). `true` by default. An adapter setting `false` claims it already
     * records every such exception live; kotrace cannot prove delivery. Applied before [acceptsEvent], report only.
     */
    val acceptsExplicitException: Boolean get() = true

    /**
     * Whether this adapter receives the attribute [key] = [value] (ADR-039). `false` by default, like
     * [acceptsSensitive]: an adapter that declares nothing receives no attribute, so admitting a key is an explicit
     * act. kotrace never interprets a key; the predicate is the adapter's own, an allowlist, a value shape, or `true`
     * for a debug adapter on a device the developer holds. kotrace's own keys get no pass:
     * [dev.kotrace.Span.EVENTS_DROPPED_ATTRIBUTE] and an integration's keys are admitted by name like any other.
     *
     * It filters every attribute map of a record handed to this adapter, live and report alike:
     * [TraceRecord.spanAttributes], the event's own [dev.kotrace.event.AttributedRecord.attributes] and each
     * [TraceLink.attributes]. It never decides whether a record is delivered, and the span keeps every attribute, so
     * a permissive adapter on the same flow still sees them. A rejection is policy, not a fault; a throw rejects that
     * one attribute and reaches the [FaultHook].
     *
     * It runs once per attribute per adapter, on the logging thread for a live record: keep it cheap and
     * non-blocking, with no regular expression that can backtrack catastrophically.
     *
     * @sample dev.kotrace.samples.PolicySamples.allowlist
     * @sample dev.kotrace.samples.PolicySamples.acceptEverything
     */
    fun acceptsAttribute(key: String, value: String): Boolean = false
}

/**
 * The gates for one [event] on one [span], on both paths. An [dev.kotrace.event.ExceptionEvent] is gated by
 * [TracePolicy.acceptsEvent] alone.
 */
internal fun TracePolicy.accepts(span: Span, event: SpanEvent): Boolean = when (event) {
    is LogEvent -> acceptsSpan(span) && acceptsEvent(event) && (!event.sensitive || acceptsSensitive)
    is NamedEvent -> acceptsSpan(span) && acceptsEvent(event)
    is ExceptionEvent -> acceptsEvent(event)
}

/** The gates for a span-less emit (ADR-010): [accepts] without [TracePolicy.acceptsSpan]. */
internal fun TracePolicy.accepts(event: SpanEvent): Boolean = when (event) {
    is LogEvent -> acceptsEvent(event) && (!event.sensitive || acceptsSensitive)
    is NamedEvent -> acceptsEvent(event)
    is ExceptionEvent -> acceptsEvent(event)
}

/**
 * [record] as this adapter may see it (ADR-039): each attribute map keeps only what [TracePolicy.acceptsAttribute]
 * admits. The same record when nothing is rejected, so an adapter accepting everything, or a record with no
 * attribute, costs no copy. A throwing predicate rejects that attribute and is told to the [FaultHook] as [phase],
 * with [faultMessage].
 */
internal fun TracePolicy.attributeView(record: TraceRecord, phase: FaultPhase, faultMessage: String): TraceRecord {
    val spanAttributes = acceptedOf(record.spanAttributes, phase, faultMessage)
    val links = acceptedOf(record.links, phase, faultMessage)
    val unchanged = spanAttributes === record.spanAttributes && links === record.links
    return when (record) {
        is LogRecord -> {
            val attributes = acceptedOf(record.attributes, phase, faultMessage)
            if (unchanged && attributes === record.attributes) record
            else record.copy(spanAttributes = spanAttributes, links = links, attributes = attributes)
        }
        is NamedRecord -> {
            val attributes = acceptedOf(record.attributes, phase, faultMessage)
            if (unchanged && attributes === record.attributes) record
            else record.copy(spanAttributes = spanAttributes, links = links, attributes = attributes)
        }
        is ExceptionRecord -> if (unchanged) record else record.copy(spanAttributes = spanAttributes, links = links)
    }
}

/** [attributes] without what this policy rejects: the same map when it rejects nothing, else a copy. */
private fun TracePolicy.acceptedOf(
    attributes: Map<String, String>,
    phase: FaultPhase,
    faultMessage: String,
): Map<String, String> {
    if (attributes.isEmpty()) return attributes
    var kept: MutableMap<String, String>? = null
    for ((key, value) in attributes) {
        if (runIsolated(phase, faultMessage, recover = { false }) { acceptsAttribute(key, value) }) continue
        val copy = kept ?: LinkedHashMap(attributes).also { kept = it }
        copy.remove(key)
    }
    return kept ?: attributes
}

/** [links] with each link's attributes filtered by [acceptedOf]: the same list when no link changes. */
private fun TracePolicy.acceptedOf(links: List<TraceLink>, phase: FaultPhase, faultMessage: String): List<TraceLink> {
    if (links.isEmpty()) return links
    var kept: MutableList<TraceLink>? = null
    links.forEachIndexed { index, link ->
        val attributes = acceptedOf(link.attributes, phase, faultMessage)
        if (attributes === link.attributes) return@forEachIndexed
        val copy = kept ?: ArrayList(links).also { kept = it }
        copy[index] = link.copy(attributes = attributes)
    }
    return kept ?: links
}
