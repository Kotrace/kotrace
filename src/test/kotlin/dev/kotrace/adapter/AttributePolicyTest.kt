package dev.kotrace.adapter

import dev.kotrace.Kotrace
import dev.kotrace.Span
import dev.kotrace.TraceConfig
import dev.kotrace.TraceLink
import dev.kotrace.TraceStatus
import dev.kotrace.currentSpan
import dev.kotrace.event.AttributedRecord
import dev.kotrace.event.ExceptionRecord
import dev.kotrace.event.LogRecord
import dev.kotrace.event.NamedRecord
import dev.kotrace.event.TraceRecord
import dev.kotrace.event.addException
import dev.kotrace.event.addNamed
import dev.kotrace.event.emitException
import dev.kotrace.event.emitNamed
import dev.kotrace.event.log
import dev.kotrace.event.toJson
import dev.kotrace.span
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * An adapter receives no attribute it has not accepted (ADR-039): [TracePolicy.acceptsAttribute] filters the span's
 * attributes, the event's own and each link's, on every record handed to that adapter, live and report, while the
 * span keeps them all.
 */
class AttributePolicyTest {

    /** A crash or remote-log sink: ships only a vetted vocabulary. */
    private val crashPolicy = object : TracePolicy {
        private val shipped = setOf("screen", "relation", Span.EVENTS_DROPPED_ATTRIBUTE)
        override fun acceptsAttribute(key: String, value: String) = key in shipped
    }

    /** A debug watch on the developer's own device: every attribute, since nothing it prints leaves the device. */
    private val debugPolicy = object : TracePolicy {
        override fun acceptsAttribute(key: String, value: String) = true
    }

    private class CollectingLive(override val policy: TracePolicy = object : TracePolicy {}) : LiveAdapter {
        val records = mutableListOf<TraceRecord>()
        override fun onLive(record: TraceRecord) { records += record }
    }

    private class CollectingReport(override val policy: TracePolicy = object : TracePolicy {}) : ReportAdapter {
        val records = mutableListOf<TraceRecord>()
        override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) { this.records += records }
    }

    private class RecordingHook : FaultHook {
        val faults = mutableListOf<Pair<FaultPhase, String?>>()
        override fun onFault(phase: FaultPhase, faultMessage: String?, cause: Throwable) {
            faults += phase to faultMessage
        }
    }

    @Before fun clean() = Kotrace.resetForTest()
    @After fun reset() = Kotrace.resetForTest()

    /** A span carrying an attribute in each of the three places a record can hold one. */
    private suspend fun checkout() = span(
        "checkout",
        attributes = mapOf("screen" to "cart", "user.email" to "alice@example.com"),
        links = listOf(TraceLink("4bf92f3577b34da6a3ce929d0e0e4736", mapOf("relation" to "retry", "ticket" to "T-1"))),
    ) {
        currentSpan()!!.log(mapOf("level" to "INFO", "screen" to "cart")) { "priced" }
    }

    @Test fun `an adapter with the default policy receives no attribute, live and report`() = runTest {
        val live = CollectingLive()
        val report = CollectingReport()

        withContext(TraceConfig(listOf(live, report))) { checkout() }

        val records = live.records + report.records
        assertEquals("one live record and one report record", 2, records.size)
        records.forEach { record ->
            assertEquals(emptyMap<String, String>(), record.spanAttributes)
            assertEquals(emptyMap<String, String>(), (record as AttributedRecord).attributes)
            assertEquals(listOf(emptyMap<String, String>()), record.links.map { it.attributes })
            assertEquals("the link itself survives: only its attributes are filtered", 1, record.links.size)
        }
    }

    @Test fun `an allowlisting adapter receives only its keys, a permissive one on the same flow receives all`() = runTest {
        val crashLive = CollectingLive(crashPolicy)
        val debugLive = CollectingLive(debugPolicy)
        val crashReport = CollectingReport(crashPolicy)
        val debugReport = CollectingReport(debugPolicy)

        withContext(TraceConfig(listOf(crashLive, debugLive, crashReport, debugReport))) { checkout() }

        // One walk, two views: the same event reaches both adapters, each with its own attributes.
        listOf(crashLive.records.single(), crashReport.records.single()).forEach { record ->
            record as LogRecord
            assertEquals(mapOf("screen" to "cart"), record.spanAttributes)
            assertEquals(mapOf("screen" to "cart"), record.attributes)
            assertEquals(mapOf("relation" to "retry"), record.links.single().attributes)
        }
        listOf(debugLive.records.single(), debugReport.records.single()).forEach { record ->
            record as LogRecord
            assertEquals(mapOf("screen" to "cart", "user.email" to "alice@example.com"), record.spanAttributes)
            assertEquals(mapOf("level" to "INFO", "screen" to "cart"), record.attributes)
            assertEquals(mapOf("relation" to "retry", "ticket" to "T-1"), record.links.single().attributes)
        }
    }

    @Test fun `the policy decides attributes, never delivery, and the span keeps every attribute`() = runTest {
        val live = CollectingLive()
        var span: Span? = null

        withContext(TraceConfig(listOf(live))) {
            span("checkout", attributes = mapOf("screen" to "cart")) {
                span = currentSpan()
                currentSpan()!!.addNamed("coupon_applied", mapOf("code" to "SPRING"))
            }
        }

        val named = live.records.single() as NamedRecord
        assertEquals("delivered all the same", "coupon_applied", named.name)
        assertTrue(named.attributes.isEmpty())
        assertEquals(mapOf("screen" to "cart"), span!!.attributes)
    }

    @Test fun `an exception record's attributes are filtered too, the crash itself always delivered`() = runTest {
        val crash = CollectingLive(crashPolicy)
        val boom = IllegalStateException("boom")

        withContext(TraceConfig(listOf(crash))) {
            span("checkout", attributes = mapOf("screen" to "cart")) {
                currentSpan()!!.addException(boom, mapOf("report" to "explicit"))
            }
        }

        val record = crash.records.single() as ExceptionRecord
        assertSame(boom, record.throwable)
        assertEquals(mapOf("screen" to "cart"), record.spanAttributes)
    }

    @Test fun `a span-less emit is filtered by the same policy`() {
        val crash = CollectingLive(crashPolicy)
        val debug = CollectingLive(debugPolicy)
        Kotrace.install(listOf(crash, debug))

        emitNamed("push_received", mapOf("screen" to "inbox", "campaign" to "c-42"))
        emitException(IllegalStateException("boom"), mapOf("screen" to "inbox", "report" to "explicit"))

        assertEquals(mapOf("screen" to "inbox"), (crash.records[0] as NamedRecord).attributes)
        assertEquals(mapOf("screen" to "inbox"), crash.records[1].spanAttributes)
        assertEquals(mapOf("screen" to "inbox", "campaign" to "c-42"), (debug.records[0] as NamedRecord).attributes)
        assertEquals(mapOf("screen" to "inbox", "report" to "explicit"), debug.records[1].spanAttributes)
    }

    @Test fun `the predicate sees the value, so a policy can reject a value shape`() = runTest {
        // Admit any key, but never a value shaped like an email address.
        val noEmails = CollectingLive(object : TracePolicy {
            override fun acceptsAttribute(key: String, value: String) = '@' !in value
        })

        withContext(TraceConfig(listOf(noEmails))) { checkout() }

        assertEquals(mapOf("screen" to "cart"), noEmails.records.single().spanAttributes)
    }

    @Test fun `a throwing predicate rejects only that attribute, the record is still delivered`() = runTest {
        val hook = RecordingHook()
        Kotrace.installFaultHook(hook)
        val flaky = object : TracePolicy {
            override fun acceptsAttribute(key: String, value: String): Boolean {
                check(key != "user.email") { "predicate bug" }
                return true
            }
        }
        val live = CollectingLive(flaky)
        val report = CollectingReport(flaky)

        withContext(TraceConfig(listOf(live, report))) { checkout() }

        listOf(live.records.single(), report.records.single()).forEach { record ->
            assertEquals(mapOf("screen" to "cart"), record.spanAttributes)
            assertEquals(mapOf("level" to "INFO", "screen" to "cart"), (record as LogRecord).attributes)
        }
        assertEquals(
            "a throw is a fault of the policy, told once per attribute and adapter",
            listOf(FaultPhase.LIVE to "live.attribute", FaultPhase.REPORT to "report.attribute"),
            hook.faults,
        )
    }

    @Test fun `a rejection is policy, not a fault`() = runTest {
        val hook = RecordingHook()
        Kotrace.installFaultHook(hook)

        withContext(TraceConfig(listOf(CollectingLive(), CollectingReport()))) { checkout() }

        assertTrue(hook.faults.isEmpty())
    }

    @Test fun `toJson of a delivered record carries only the accepted attributes`() = runTest {
        val crash = CollectingReport(crashPolicy)

        withContext(TraceConfig(listOf(crash))) { checkout() }

        val json = crash.records.single().toJson()
        assertTrue(json, json.contains(""""attributes":{"screen":"cart"}"""))
        assertTrue(json, json.contains(""""span_attributes":{"screen":"cart"}"""))
        assertTrue(json, json.contains(""""links":[{"trace_id":"4bf92f3577b34da6a3ce929d0e0e4736","attributes":{"relation":"retry"}}]"""))
        listOf("alice@example.com", "user.email", "level", "ticket").forEach { assertFalse(json, json.contains(it)) }
    }

    @Test fun `a record is copied only for an adapter whose view differs`() = runTest {
        val first = CollectingLive(debugPolicy)
        val second = CollectingLive(debugPolicy)
        val crash = CollectingLive(crashPolicy)
        val bare = CollectingLive()

        withContext(TraceConfig(listOf(first, second, crash, bare))) {
            checkout()
            span("plain") { currentSpan()!!.log { "no attributes anywhere" } }
        }

        assertSame("everything accepted: the shared record, uncopied", first.records[0], second.records[0])
        assertNotSame("a rejection: a copy of its own", first.records[0], crash.records[0])
        assertSame("no attribute to reject: the shared record", first.records[1], bare.records[1])
    }
}
