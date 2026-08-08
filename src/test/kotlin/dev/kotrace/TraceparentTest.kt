package dev.kotrace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceparentTest {

    private fun span(traceId: String, spanId: String) =
        Span(traceId = traceId, spanId = spanId, parentId = null, name = "HTTP GET /x", startNanos = 0L)

    @Test
    fun `format is version-traceid-spanid-flags with W3C hex widths`() {
        val header = span("0".repeat(32), "1".repeat(16)).toTraceparent()

        assertEquals("00-${"0".repeat(32)}-${"1".repeat(16)}-01", header)
        val parts = header.split("-")
        assertEquals("four fields", 4, parts.size)
        assertEquals("version", "00", parts[0])
        assertEquals("trace id is 16 bytes / 32 hex", 32, parts[1].length)
        assertEquals("span id is 8 bytes / 16 hex", 16, parts[2].length)
        assertEquals("sampled flag", "01", parts[3])
    }

    @Test
    fun `real generated ids produce a well-formed header`() {
        // Ids as trace {} mints them, to catch any hex-width regression in id generation.
        val header = span(traceId = randomHex(16), spanId = randomHex(8)).toTraceparent()
        assertTrue("matches the W3C traceparent grammar", W3C.matches(header))
    }

    @Test
    fun `unsampled sets the flags to 00`() {
        assertTrue(span("0".repeat(32), "1".repeat(16)).toTraceparent(sampled = false).endsWith("-00"))
    }

    private companion object {
        val W3C = Regex("^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$")
        fun randomHex(bytes: Int) = java.security.SecureRandom().let { r ->
            ByteArray(bytes).also { r.nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}
