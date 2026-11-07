package dev.kotrace.benchmarks

import dev.kotrace.TraceConfig
import dev.kotrace.TraceStatus
import dev.kotrace.adapter.ReportAdapter
import dev.kotrace.adapter.TracePolicy
import dev.kotrace.event.ExceptionOrigin
import dev.kotrace.event.ExceptionRecord
import dev.kotrace.event.LogRecord
import dev.kotrace.event.TraceRecord
import dev.kotrace.report.reportTrace
import kotlin.coroutines.EmptyCoroutineContext

private object CrashSinkPolicy : TracePolicy {
    override fun acceptsAttribute(key: String, value: String): Boolean = true
}

/** Camailux report shape: materialize once, flush breadcrumbs, then select and record crashes. */
internal class CountingCrashAdapter : ReportAdapter {
    override val policy: TracePolicy = CrashSinkPolicy
    var reads = 0
        private set
    var breadcrumbs = 0
        private set
    var exceptions = 0
        private set
    var selected = 0
        private set
    var selectedAttached = 0
        private set
    var selectedBirthplaces = 0
        private set
    var checksum = 0L
        private set

    override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
        reads++
        breadcrumbs = 0
        exceptions = 0
        selected = 0
        selectedAttached = 0
        selectedBirthplaces = 0
        checksum = status.ordinal.toLong()
        val all = records.toList()
        all.forEach { record ->
            if (record is LogRecord) {
                breadcrumbs++
                checksum = checksum xor record.atNanos xor (record.operation?.length?.toLong() ?: 0L)
            }
        }
        val crashes = all.filterIsInstance<ExceptionRecord>().filter {
            exceptions++
            status == TraceStatus.ERROR || it.origin == ExceptionOrigin.ATTACHED
        }
        crashes.forEach { record ->
            selected++
            if (record.origin == ExceptionOrigin.ATTACHED) selectedAttached++
            if (record.origin == ExceptionOrigin.BIRTHPLACE) selectedBirthplaces++
            checksum = checksum xor record.atNanos xor (record.operation?.length?.toLong() ?: 0L)
        }
    }
}

/** Status-first skip like REPORT_SKIP on non-ERROR; ERROR uses the normal counting consumer. */
private class ErrorOnlyCountingAdapter : ReportAdapter {
    override val policy: TracePolicy = CrashSinkPolicy
    var count = 0
        private set
    var checksum = 0L
        private set

    override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
        count = 0
        checksum = status.ordinal.toLong()
        if (status != TraceStatus.ERROR) return
        records.forEach { record ->
            count++
            checksum = checksum xor record.atNanos xor (record.operation?.length?.toLong() ?: 0L)
        }
    }
}

/** Thread-bound sinks retain only scalar counts/checksums, never a report or its records. */
class CrashSinkSession(layout: String) : AutoCloseable {
    internal val crash = CountingCrashAdapter()
    private val skipping = when (layout) {
        "ALONE" -> emptyList()
        "MIXED" -> List(3) { ErrorOnlyCountingAdapter() }
        else -> error("Unknown crash sink layout: $layout")
    }
    private val config = TraceConfig(listOf(crash) + skipping)
    private var previous: TraceConfig? = null
    private var entered = false

    fun enter() {
        check(!entered)
        previous = config.updateThreadContext(EmptyCoroutineContext)
        entered = true
    }

    fun report(fixture: ReportFixture, status: TraceStatus): Long {
        fixture.collector.reportTrace(status)
        return crash.checksum + crash.breadcrumbs + crash.selected +
            skipping.sumOf { it.count.toLong() + it.checksum }
    }

    internal fun skippingRecordCount(): Int = skipping.sumOf { it.count }

    override fun close() {
        if (entered) config.restoreThreadContext(EmptyCoroutineContext, previous)
        entered = false
        previous = null
    }
}
