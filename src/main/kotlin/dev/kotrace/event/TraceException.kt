package dev.kotrace.event

import dev.kotrace.Span
import dev.kotrace.TraceLink
import dev.kotrace.UnredactedTraceRead
import dev.kotrace.isJvmFatal
import dev.kotrace.resolvedThreadConfig
import java.util.Collections
import java.util.IdentityHashMap

/**
 * A throwable on a span's timeline, ordered among its log lines. It carries the raw [throwable], which a crash
 * reporter needs for symbolication and grouping, and no attributes, so only a policy that inspects it in
 * [dev.kotrace.TracePolicy.acceptsEvent] can drop it.
 */
class ExceptionEvent internal constructor(
    val throwable: Throwable,
    override val atNanos: Long,
    /**
     * The identity-compared key the report dedups a climb by (ADR-015): events sharing it collapse to the deepest
     * span carrying it. A fresh identity by default, so an exception a consumer records is never deduped; only
     * [recordPropagatedException] passes the canonical [lineageKeyOf]. Internal, so no consumer can choose one.
     */
    internal val lineageKey: Any = Any(),
) : SpanEvent

/**
 * The artificial frame kotlinx.coroutines splices into a stacktrace-recovery copy at a `withContext` boundary
 * (checked against 1.11.0). Only this frame evidences a copy; `_COROUTINE._CREATION` also appears on ordinary
 * exceptions built inside a coroutine.
 */
private const val COROUTINE_BOUNDARY_FRAME = "_COROUTINE._BOUNDARY"

/** The legacy boundary marker, from before the `_COROUTINE` frames. */
private const val COROUTINE_BOUNDARY_LEGACY = "Coroutine boundary"

/** Bounds the cause walk so a pathological chain can never make [lineageKeyOf] loop long. */
private const val MAX_RECOVERY_DEPTH = 100

/**
 * The object whose identity is [throwable]'s lineage key (ADR-015): walk down [Throwable.cause] while the current
 * throwable is a recognized stacktrace-recovery copy of it, and stop at the first that is not. Every boundary's
 * copy thus resolves to the same original. Anything not provably a copy, such as `throw B(cause = A)`, keeps its
 * own identity (**fail open**): a duplicate record at worst, never a dropped failure.
 *
 * **Total.** `cause`, `message` and `stackTrace` are overridable and can throw; this runs on the failure path,
 * including [dev.kotrace.end], so a non-fatal accessor throw ends the walk at the last object held.
 */
internal fun lineageKeyOf(throwable: Throwable): Any {
    var current: Throwable = throwable
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var depth = 0
    try {
        while (seen.add(current) && depth < MAX_RECOVERY_DEPTH) {
            val cause = current.cause ?: break
            if (!current.isRecoveryWrapperOf(cause)) break
            current = cause
            depth++
        }
    } catch (fault: Throwable) {
        if (fault.isJvmFatal()) throw fault
    }
    return current
}

/**
 * Whether this is a coroutine stacktrace-recovery copy of [cause]: the same class and message, and a boundary
 * frame in its own stack trace. Biased to false negatives, which cost a duplicate record; a false positive would
 * drop an escaping failure. The message check keeps a same-class semantic wrap (`throw Foo(msg, cause = A)`) from
 * collapsing into `A`. A forged boundary frame could fool it; that residual is accepted (ADR-015).
 */
private fun Throwable.isRecoveryWrapperOf(cause: Throwable): Boolean =
    javaClass == cause.javaClass &&
        message == cause.message &&
        stackTrace.any { it.className == COROUTINE_BOUNDARY_FRAME || it.className.contains(COROUTINE_BOUNDARY_LEGACY) }

/**
 * Whether a reported [ExceptionRecord] is the failure's **origin** ([BIRTHPLACE], the deepest span carrying it) or
 * a **copy** re-recorded on an ancestor as it climbed ([PROPAGATED]) (ADR-019). A live record is unclassified.
 */
enum class ExceptionOrigin { BIRTHPLACE, PROPAGATED }

/**
 * The crash record: identity plus the raw [throwable]. It carries no attributes, so only a policy that covers
 * exceptions ([dev.kotrace.TracePolicy.acceptsEvent]) can drop it.
 *
 * The [throwable] is the one field allowed to carry user data (see [dev.kotrace.Span]), so it goes **only** to a
 * crash reporter, never to the [toJson] log path. An adapter routing it there **must stamp [traceId] as a
 * searchable key on the crash report** (Crashlytics `setCustomKey("trace_id", traceId)`): it is the sole join key
 * between the log store and the crash reporter.
 */
data class ExceptionRecord(
    override val traceId: String?,
    override val spanId: String?,
    override val parentId: String?,
    override val operation: String?,
    override val atNanos: Long,
    override val scopeId: String? = null,
    override val info: Map<String, String>,
    override val links: List<TraceLink>,
    val throwable: Throwable,
    /**
     * `null` on a live record, emitted before the tree is complete (ADR-019). On a report record,
     * [ExceptionOrigin.BIRTHPLACE] or [ExceptionOrigin.PROPAGATED]; by default an adapter receives only the
     * birthplaces, one with `acceptsPropagatedException` also the copies.
     */
    val origin: ExceptionOrigin? = null,
) : TraceRecord

/**
 * Records [cause] on this span's timeline and offers it to each live adapter as it happens, so a live watch sees a
 * crash when it is thrown. Like every event verb it appends unconditionally (ADR-002), so a layer-filtered trace
 * never loses its crash; a policy keeps it by default.
 *
 * A throwable that escapes [dev.kotrace.span] is re-recorded on every enclosing span, so a live watch sees one line
 * per ancestor, deepest first. By default the report keeps only the birthplace (ADR-019).
 *
 * [info] merges over the span's [dev.kotrace.Span.info] onto the live [ExceptionRecord.info] only, such as a marker
 * kotrace does not interpret (ADR-010); the event itself holds none, so the report is unaffected.
 *
 * Each call is its **own lineage**, never deduped against another (ADR-015).
 */
fun Span.addException(cause: Throwable, info: Map<String, String> = emptyMap()) {
    emit(ExceptionEvent(cause, System.nanoTime()), resolvedThreadConfig(), info)
}

/**
 * Records a **climbing** throwable with its canonical [lineageKeyOf], so every enclosing span's record of one
 * failure shares a key and the report collapses it to its birthplace (ADR-015).
 */
internal fun Span.recordPropagatedException(cause: Throwable) {
    emit(ExceptionEvent(cause, System.nanoTime(), lineageKeyOf(cause)), resolvedThreadConfig())
}

/**
 * The first throwable still on this span's timeline, if any: its birthplace throwable where the failure was born,
 * a propagated copy on an ancestor (ADR-019). A span past [Span.MAX_EVENTS] holding only exceptions has discarded
 * its oldest ones (ADR-029). Unredacted ([UnredactedTraceRead]): the raw throwable, message included.
 */
@UnredactedTraceRead
val Span.exception: Throwable?
    get() = eventBuffer.firstNotNullOfOrNull { (it as? ExceptionEvent)?.throwable }
