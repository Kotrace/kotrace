package dev.kotrace.benchmarks

import dev.kotrace.LiveAdapter
import dev.kotrace.ReportAdapter
import dev.kotrace.Span
import dev.kotrace.SpanCollector
import dev.kotrace.SpanStatus
import dev.kotrace.TraceConfig
import dev.kotrace.TracePolicy
import dev.kotrace.TraceStatus
import dev.kotrace.event.ExceptionEvent
import dev.kotrace.event.TraceRecord
import dev.kotrace.event.addException
import dev.kotrace.event.addNamed
import dev.kotrace.event.log
import dev.kotrace.reportTrace
import dev.kotrace.span
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.locks.LockSupport
import kotlin.coroutines.EmptyCoroutineContext

private const val TRACE_ID = "00000000000000000000000000000001"
private const val SPAN_ID = "0000000000000001"

private object AcceptAllPolicy : TracePolicy

private object RejectAllEventsPolicy : TracePolicy {
    override fun acceptsEvent(event: dev.kotrace.event.SpanEvent): Boolean = false
}

private object AcceptSensitivePolicy : TracePolicy {
    override val acceptsSensitive: Boolean = true
}

private class CountingLiveAdapter(
    override val policy: TracePolicy,
    private val delayNanos: Long = 0,
) : LiveAdapter {
    private val count = LongAdder()
    private val checksum = LongAdder()

    override fun onLive(record: TraceRecord) {
        if (delayNanos > 0) LockSupport.parkNanos(delayNanos)
        count.increment()
        checksum.add(record.atNanos xor (record.traceId?.length?.toLong() ?: 0L))
    }

    fun count(): Long = count.sum()
    fun checksum(): Long = checksum.sum()
}

private class CountingReportAdapter(
    override val policy: TracePolicy,
    private val consume: Boolean,
) : ReportAdapter {
    var count: Int = 0
        private set
    var checksum: Long = 0
        private set

    override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
        count = 0
        checksum = status.ordinal.toLong()
        if (!consume) return
        records.forEach { record ->
            count++
            checksum = checksum xor record.atNanos xor (record.operation?.length?.toLong() ?: 0L)
        }
    }
}

/** Thread-bound config plus deterministic in-memory sinks for JMH worker state. */
class BenchSession(
    mode: String,
    adapterCount: Int = 1,
    delayNanos: Long = 0,
) : AutoCloseable {
    private val liveAdapters: List<CountingLiveAdapter>
    private val reportAdapters: List<CountingReportAdapter>
    private val config: TraceConfig?
    private var previous: TraceConfig? = null
    private var entered = false

    init {
        val count = adapterCount.coerceAtLeast(0)
        liveAdapters = when (mode) {
            "LIVE_ACCEPT", "BOTH", "LIVE_DELAY" -> List(count) {
                CountingLiveAdapter(AcceptAllPolicy, if (mode == "LIVE_DELAY") delayNanos else 0)
            }
            "LIVE_SENSITIVE" -> List(count) { CountingLiveAdapter(AcceptSensitivePolicy) }
            "LIVE_REJECT" -> List(count) { CountingLiveAdapter(RejectAllEventsPolicy) }
            else -> emptyList()
        }
        reportAdapters = when (mode) {
            "REPORT_CONSUME", "BOTH" -> List(count) { CountingReportAdapter(AcceptAllPolicy, consume = true) }
            "REPORT_SKIP" -> List(count) { CountingReportAdapter(AcceptAllPolicy, consume = false) }
            else -> emptyList()
        }
        config = if (mode == "NONE") null else TraceConfig(liveAdapters + reportAdapters)
    }

    fun enter() {
        check(!entered)
        if (config != null) previous = config.updateThreadContext(EmptyCoroutineContext)
        entered = true
    }

    override fun close() {
        if (entered && config != null) config.restoreThreadContext(EmptyCoroutineContext, previous)
        entered = false
        previous = null
    }

    fun captureLogBatch(eventCount: Int): Long {
        val span = freshSpan()
        val before = delivered()
        repeat(eventCount) { index -> span.log { "event-$index" } }
        return span.events.size.toLong() + delivered() - before + sinkChecksum()
    }

    fun captureKindBatch(eventCount: Int, kind: String): Long {
        val span = freshSpan()
        val throwable = IllegalStateException("benchmark")
        val before = delivered()
        repeat(eventCount) { index ->
            when (kind) {
                "LOG" -> span.log { "event-$index" }
                "NAMED" -> span.addNamed("event-$index")
                "EXCEPTION" -> span.addException(throwable)
                else -> error("Unknown event kind: $kind")
            }
        }
        return span.events.size.toLong() + delivered() - before + sinkChecksum()
    }

    fun sensitiveBatch(eventCount: Int, sensitive: Boolean): Long {
        val span = freshSpan()
        val before = delivered()
        repeat(eventCount) { index -> span.log(sensitive = sensitive) { "body-$index" } }
        return span.events.size.toLong() + delivered() - before + sinkChecksum()
    }

    fun report(fixture: ReportFixture, status: TraceStatus): Long {
        fixture.collector.reportTrace(status)
        return reportAdapters.sumOf { it.count.toLong() + it.checksum }
    }

    fun reportRecordCount(): Int = reportAdapters.sumOf(CountingReportAdapter::count)

    fun configuredBaseline(value: Int): Int = runBlocking {
        val active = config
        if (active == null) value + 1 else withContext(active) { value + 1 }
    }

    fun autoRoot(value: Int): Int = runBlocking {
        val active = config
        if (active == null) {
            span("root") { value + 1 }
        } else {
            withContext(active) { span("root") { value + 1 } }
        }
    }

    private fun delivered(): Long = liveAdapters.sumOf(CountingLiveAdapter::count)
    private fun sinkChecksum(): Long = liveAdapters.sumOf(CountingLiveAdapter::checksum)
}

class ReportFixture internal constructor(
    internal val collector: SpanCollector,
    val spanCount: Int,
)

class EventReadFixture internal constructor(
    private val span: Span,
) {
    fun readAll(): Long = span.events.fold(0L) { checksum, event -> checksum xor event.atNanos }
}

object BenchWorkloads {
    @JvmStatic
    fun plain(value: Int): Int = value + 1

    @JvmStatic
    fun coroutineBaseline(value: Int): Int = runBlocking { value + 1 }

    @JvmStatic
    fun autoRootNoConfig(value: Int): Int = runBlocking { span("root") { value + 1 } }

    @JvmStatic
    fun buildReportFixture(shape: String, spanCount: Int): ReportFixture {
        require(spanCount > 0)
        val collector = SpanCollector()
        runBlocking {
            withContext(collector + TraceConfig(emptyList())) {
                when (shape) {
                    "CHAIN" -> chain(spanCount, 0)
                    "STAR" -> star(spanCount)
                    "BALANCED" -> balanced(spanCount, 0)
                    else -> error("Unknown tree shape: $shape")
                }
            }
        }
        check(collector.spans.size == spanCount) {
            "Expected $spanCount spans, got ${collector.spans.size}"
        }
        return ReportFixture(collector, spanCount)
    }

    @JvmStatic
    fun buildEventReadFixture(eventCount: Int): EventReadFixture {
        val span = freshSpan()
        repeat(eventCount) { index -> span.log { "event-$index" } }
        check(span.events.size == eventCount)
        return EventReadFixture(span)
    }

    /** Star fixture with one distinct birthplace failure on every child; construction stays outside timing. */
    @JvmStatic
    fun buildExceptionReportFixture(spanCount: Int): ReportFixture {
        require(spanCount > 1)
        val collector = SpanCollector()
        runBlocking {
            withContext(collector + TraceConfig(emptyList())) {
                span("failure-root") {
                    repeat(spanCount - 1) { index ->
                        runCatching {
                            span("failure-${index + 1}") {
                                throw IllegalStateException("failure-${index + 1}")
                            }
                        }
                    }
                }
            }
        }
        check(collector.spans.size == spanCount) {
            "Expected $spanCount spans, got ${collector.spans.size}"
        }
        return ReportFixture(collector, spanCount)
    }

    @JvmStatic
    fun failureBaseline(depth: Int): Int = runBlocking {
        runCatching { baselineThrow(depth) }.exceptionOrNull()!!.javaClass.name.length
    }

    @JvmStatic
    fun failureClimb(depth: Int): Int = runBlocking {
        runCatching { failureSpan(depth) }.exceptionOrNull()!!.javaClass.name.length
    }

    @JvmStatic
    fun b01ObservedExceptionTypes(): List<String> {
        val captured = ArrayList<String>()
        val adapter = object : ReportAdapter {
            override val policy: TracePolicy = AcceptAllPolicy
            override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
                records.filterIsInstance<dev.kotrace.event.ExceptionRecord>()
                    .mapTo(captured) { it.throwable.javaClass.simpleName }
            }
        }
        runBlocking {
            withContext(TraceConfig(listOf(adapter))) {
                runCatching {
                    span("root") {
                        runCatching { span("child") { throw IllegalArgumentException("A") } }
                        throw IllegalStateException("B")
                    }
                }
            }
        }
        return captured
    }

    @JvmStatic
    fun lazyMessageCalls(mode: String, eventCount: Int): Int {
        val session = BenchSession(mode)
        session.enter()
        return try {
            var calls = 0
            val span = freshSpan()
            repeat(eventCount) { span.log { calls++; "message-$calls" } }
            calls
        } finally {
            session.close()
        }
    }

    private suspend fun chain(remaining: Int, index: Int) {
        span("chain-$index") {
            currentLog(index)
            if (remaining > 1) chain(remaining - 1, index + 1)
        }
    }

    private suspend fun star(spanCount: Int) {
        span("star-root") {
            currentLog(0)
            repeat(spanCount - 1) { index ->
                span("star-${index + 1}") { currentLog(index + 1) }
            }
        }
    }

    private suspend fun balanced(spanCount: Int, index: Int) {
        span("balanced-$index") {
            currentLog(index)
            val remaining = spanCount - 1
            if (remaining > 0) {
                val left = remaining / 2
                val right = remaining - left
                if (left > 0) balanced(left, index * 2 + 1)
                if (right > 0) balanced(right, index * 2 + 2)
            }
        }
    }

    private suspend fun currentLog(index: Int) {
        dev.kotrace.currentSpan()!!.log { "event-$index" }
    }

    private suspend fun baselineThrow(depth: Int): Nothing {
        if (depth <= 1) throw IllegalStateException("benchmark")
        withContext(CoroutineName("baseline-$depth")) { baselineThrow(depth - 1) }
    }

    private suspend fun failureSpan(depth: Int): Nothing =
        span("failure-$depth") {
            if (depth <= 1) throw IllegalStateException("benchmark")
            failureSpan(depth - 1)
        }
}

class ConcurrencyHarness(
    private val mode: String,
) : AutoCloseable {
    private val executor = Executors.newFixedThreadPool(4)
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    fun run(workerCount: Int, eventsPerWorker: Int): Long = when (mode) {
        "BASELINE" -> runBaseline(workerCount, eventsPerWorker)
        "SHARED" -> runSharedSpan(workerCount, eventsPerWorker)
        "CHILDREN" -> runChildSpans(workerCount, eventsPerWorker)
        else -> error("Unknown concurrency mode: $mode")
    }

    private fun runBaseline(workerCount: Int, eventsPerWorker: Int): Long = runBlocking(dispatcher) {
        coroutineScope {
            List(workerCount) { worker ->
                async { (0 until eventsPerWorker).sumOf { it.toLong() + worker } }
            }.awaitAll().sum()
        }
    }

    private fun runSharedSpan(workerCount: Int, eventsPerWorker: Int): Long {
        val adapter = CountingLiveAdapter(AcceptAllPolicy)
        val config = TraceConfig(listOf(adapter))
        val root = freshSpan()
        return runBlocking(dispatcher) {
            withContext(config) {
                coroutineScope {
                    List(workerCount) { worker ->
                        async {
                            repeat(eventsPerWorker) { event -> root.log { "w$worker-e$event" } }
                        }
                    }.awaitAll()
                }
                root.events.size.toLong() + adapter.count() + adapter.checksum()
            }
        }
    }

    private fun runChildSpans(workerCount: Int, eventsPerWorker: Int): Long {
        val collector = SpanCollector()
        val config = TraceConfig(emptyList())
        return runBlocking(dispatcher) {
            withContext(collector + config) {
                span("root") {
                    coroutineScope {
                        List(workerCount) { worker ->
                            async {
                                span("worker-$worker") {
                                    repeat(eventsPerWorker) { event ->
                                        dev.kotrace.currentSpan()!!.log { "w$worker-e$event" }
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
                collector.spans.sumOf { it.events.size.toLong() } + collector.spans.size
            }
        }
    }

    override fun close() {
        (dispatcher as AutoCloseable).close()
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }
}

private fun freshSpan(): Span = Span(
    traceId = TRACE_ID,
    spanId = SPAN_ID,
    parentId = null,
    name = "benchmark",
    startNanos = 1L,
)
