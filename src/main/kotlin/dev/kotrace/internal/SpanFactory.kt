package dev.kotrace.internal

import dev.kotrace.Span
import dev.kotrace.TraceLink
import dev.kotrace.resolvedMaxEventsPerSpan
import java.security.SecureRandom

/**
 * Builds a span under [parent], or a new trace's root when it is null. Callers resolve [parent] themselves, so
 * this never reads ambient state. [links] belong to this span only, never inherited. [initialAttributes] are
 * stamped before the span is returned, so they ride every record from its first event. The span's cap is the
 * configuration's [dev.kotrace.TraceConfig.maxEventsPerSpan] at this moment (ADR-033).
 */
internal fun createSpan(
    parent: Span?,
    name: String,
    traits: Map<String, String>,
    links: List<TraceLink> = emptyList(),
    initialAttributes: Map<String, String> = emptyMap(),
    scopeId: String? = null,
): Span = Span(
    traceId = parent?.traceId ?: hex(16),
    spanId = hex(8),
    parentId = parent?.spanId,
    name = name,
    startNanos = System.nanoTime(),
    traits = traits,
    links = links,
    scopeId = scopeId,
    maxEvents = resolvedMaxEventsPerSpan(),
).also { opened -> initialAttributes.forEach { (key, value) -> opened.putAttribute(key, value) } }

private val random = SecureRandom()

/**
 * [bytes] random bytes as lowercase hex: 16 for a trace id, 8 for a span id. An all-zero id is invalid in W3C
 * Trace Context, so it is redrawn.
 */
internal fun hex(bytes: Int): String {
    val b = ByteArray(bytes)
    do { random.nextBytes(b) } while (b.all { it == 0.toByte() })
    // A table lookup: "%02x".format per byte was ~88% of an empty span's allocation.
    val out = CharArray(bytes * 2)
    for (i in b.indices) {
        val v = b[i].toInt() and 0xFF
        out[2 * i] = HEX_DIGITS[v ushr 4]
        out[2 * i + 1] = HEX_DIGITS[v and 0x0F]
    }
    return String(out)
}

private val HEX_DIGITS = "0123456789abcdef".toCharArray()
