package dev.kotrace.adapter

import dev.kotrace.Span
import dev.kotrace.event.ExceptionEvent
import dev.kotrace.event.LogEvent
import dev.kotrace.event.NamedEvent
import dev.kotrace.event.SpanEvent

/**
 * An adapter's fan-out **decisions**, separate from where it routes a record: gates applied alike on the live and
 * report paths (see [accepts]). The defaults pass spans and events and withhold sensitive records, so a bare
 * policy is a working, fail-closed one.
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
