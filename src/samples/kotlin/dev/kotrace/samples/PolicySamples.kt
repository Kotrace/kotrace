package dev.kotrace.samples

import dev.kotrace.Span
import dev.kotrace.adapter.TracePolicy

/**
 * Dokka `@sample` targets for [TracePolicy], compiled against the real API by `check` (ADR-007), so the example
 * cannot drift. Not published: excluded from the jar.
 */
@Suppress("unused")
internal object PolicySamples {

    /**
     * A policy for an adapter that ships off the device (a crash or remote-log sink): it admits a vetted vocabulary
     * by name and nothing else (ADR-039). kotrace's own keys are listed like any other; an integration's keys, such
     * as `kotrace-okhttp`'s `HttpSpan.STATUS`, are public constants to list the same way.
     */
    fun allowlist(): TracePolicy = object : TracePolicy {
        private val shipped = setOf("feature", "result", Span.EVENTS_DROPPED_ATTRIBUTE)

        override fun acceptsAttribute(key: String, value: String) = key in shipped
    }

    /**
     * A policy for a debug adapter printing to the console of a device the developer holds: it accepts every
     * attribute, since nothing it prints leaves that device, and sensitive messages for the same reason.
     */
    fun acceptEverything(): TracePolicy = object : TracePolicy {
        override val acceptsSensitive = true

        override fun acceptsAttribute(key: String, value: String) = true
    }
}
