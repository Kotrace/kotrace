package dev.kotrace.event

import dev.kotrace.Span
import dev.kotrace.TraceLink
import dev.kotrace.isFatalFault
import dev.kotrace.resolvedThreadConfig
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The birthplace throwable as a timeline event — the crash cause, ordered among the span's log lines.
 * Carries the raw [throwable] (not stringified attributes): a crash reporter needs the live object for
 * symbolication and grouping. It has no attributes, so a [dev.kotrace.TracePolicy] filtering on them leaves it alone;
 * only a policy that explicitly inspects an [ExceptionEvent] in [dev.kotrace.TracePolicy.acceptsEvent] can drop it.
 */
class ExceptionEvent(
    val throwable: Throwable,
    override val atNanos: Long,
) : SpanEvent {
    /**
     * Stable **lineage key** for report/render dedup (ADR-015). Birthplace selection collapses events that
     * share a key to the deepest span carrying it, so one failure climbing the tree reports once. The key is
     * an object compared by **identity** (see [dev.kotrace.Span.birthplaceExceptionsAmong]).
     *
     * The default is a **fresh, distinct** identity: a consumer-recorded event (public [addException]) is its
     * own lineage and is never deduped away. Only the internal propagation recorder ([recordPropagatedException])
     * overwrites it via [stampLineage] with the canonical key, so a *climb* — the same failure re-recorded on
     * each enclosing span — collapses. Not a constructor parameter, so the public 2-arg constructor's JVM
     * descriptor is unchanged.
     */
    internal var lineageKey: Any = Any()
        private set

    /** Stamps the canonical [lineageKey]; called by [recordPropagatedException] before the event is emitted. */
    internal fun stampLineage(key: Any): ExceptionEvent = apply { lineageKey = key }
}

/**
 * The exact class name of the artificial **boundary** frame kotlinx.coroutines splices into a
 * stacktrace-recovery copy as it crosses a `withContext` boundary (verified against 1.11.0). We match the
 * boundary frame **specifically** — not `_COROUTINE._CREATION` (the debug creation-stack frame) nor any other
 * `_COROUTINE*` frame — because only a boundary crossing evidences a recovery copy; a creation frame appears
 * on ordinary exceptions built inside a coroutine. Narrower matching means fewer false positives (a false
 * positive would drop an escaping failure — the B01 direction).
 */
private const val COROUTINE_BOUNDARY_FRAME = "_COROUTINE._BOUNDARY"

/** Legacy (pre-`_COROUTINE`) boundary marker: the class name of the `…(Coroutine boundary)…` artificial frame. */
private const val COROUTINE_BOUNDARY_LEGACY = "Coroutine boundary"

/** Bounds the cause walk so a pathological chain can never make [lineageKeyOf] loop long. */
private const val MAX_RECOVERY_DEPTH = 100

/**
 * The **canonical object** whose identity is [throwable]'s lineage key (ADR-015). A single local cause walk,
 * no comparison against anything deeper:
 *
 * 1. Start at [throwable].
 * 2. While the current object is a **recognized coroutine stacktrace-recovery wrapper** — *its own* stack
 *    trace carries the recovery artificial frame **and** its direct [Throwable.cause] is of the **same**
 *    runtime class — step to that cause. (The wrapper carries the frame; the original underneath generally
 *    does not.)
 * 3. Stop at the first object that is not a recognized wrapper; its identity is the key.
 *
 * This collapses the right things and nothing else. Recovery **off** (or a non-copyable throwable): the same
 * instance is observed at every boundary, step 2 never fires, one identity → one lineage. Recovery **on**:
 * every boundary's fresh copy resolves down its recovery chain to the *same* original → one lineage. Anything
 * not *provably* a recovery wrapper — including a semantic `cause` such as `throw B(cause = A)` — is left as
 * its own identity (**fail open**): duplicate records at worst, the escaping failure never dropped. Identity
 * cycle detection and [MAX_RECOVERY_DEPTH] keep the walk finite.
 *
 * **Total by contract.** [Throwable.cause] / [Throwable.message] / [Throwable.stackTrace] are all overridable,
 * so a hostile throwable can throw from any of them. This must never propagate: the internal recorder runs on
 * the failure path — including [dev.kotrace.end], which (unlike the suspend `span` catch) has no strict-mode
 * guard around it. A non-fatal accessor throw is swallowed and we fail open on the last object safely held;
 * only a JVM-fatal fault is rethrown.
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
        if (fault.isFatalFault()) throw fault
        // A hostile cause/message/stackTrace accessor threw — fail open on the last object safely held.
    }
    return current
}

/**
 * Whether this throwable is a coroutine stacktrace-recovery copy of [cause] — see [lineageKeyOf]. Three
 * conjuncts, deliberately biased to **false negatives** (a missed copy costs only a duplicate record; a false
 * positive would drop an escaping failure — the B01 direction):
 *
 * 1. *its own* stack trace carries a coroutine **boundary** artificial frame ([COROUTINE_BOUNDARY_FRAME] /
 *    legacy marker) — spliced in as the copy crosses a `withContext`; the original underneath does not;
 * 2. the direct [Throwable.cause] is of the **same runtime class** — recovery copies the class;
 * 3. the copy carries the same **message** as that cause — recovery copies the message.
 *
 * The message conjunct keeps a *same-class semantic* wrap — `throw Foo(newMessage, cause = A)` — from being
 * mistaken for a recovery copy and collapsing the escaping failure into `A`. (A different-class wrap is already
 * excluded by conjunct 2.) It is deliberately strict in the safe direction: a copy made through a cause-only
 * constructor may carry the cause's `toString()` rather than its `message` and so fail conjunct 3 — that only
 * costs a duplicate record (fail open), never a dropped failure. These are strong heuristics, not proof:
 * `stackTrace` is publicly mutable, so a caller *could* forge a boundary frame; that residual is unavoidable
 * and accepted (ADR-015).
 */
private fun Throwable.isRecoveryWrapperOf(cause: Throwable): Boolean =
    javaClass == cause.javaClass &&
        message == cause.message &&
        stackTrace.any { it.className == COROUTINE_BOUNDARY_FRAME || it.className.contains(COROUTINE_BOUNDARY_LEGACY) }

/**
 * The crash record — identity + the birthplace [throwable]. Synthesised from an [ExceptionEvent] at
 * fan-out; it carries no attributes, so a policy filtering on them keeps it by default — only a policy that
 * covers exceptions ([dev.kotrace.TracePolicy.acceptsEvent]) can drop the crash cause.
 *
 * Two-sink PII split (see [dev.kotrace.Span]'s invariant): the raw [throwable] — the one field allowed to
 * carry user data — must go **only** to a crash reporter, never to the general [toJson] log path (which
 * renders the class name only). An adapter routing it there **must stamp [traceId] as a searchable key on
 * the crash report** (e.g. Crashlytics `setCustomKey("trace_id", traceId)` before `recordException`);
 * `trace_id` is the sole join key between the two sinks. Without it, operations can see *that* a trace
 * failed in the log store but cannot pull its detail from the crash reporter.
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
) : TraceRecord

/**
 * Records the birthplace [cause] as an [ExceptionEvent] on this span's timeline, and — like [log]/[addNamed]
 * — offers it to each live adapter as it happens, so a debug live watch sees a crash the moment it is thrown
 * rather than only at report fan-out ([dev.kotrace.reportTrace]).
 *
 * Like every event verb it appends unconditionally (there is no capture gate — ADR-002); a layer-filtered
 * trace must never lose its crash. A live adapter then sees it iff its policy [dev.kotrace.accepts] the
 * [ExceptionEvent], which by default keeps it (a crash carries no `acceptsSpan`/sensitive gate, only the
 * opt-in [dev.kotrace.TracePolicy.acceptsEvent]). The report path is unchanged: the appended event still fans out
 * once, at the birthplace ([dev.kotrace.reportTrace]).
 *
 * Note the throwable climbs the tree: it is re-recorded on every enclosing span as it rethrows
 * ([dev.kotrace.span]), so a live watch sees one line per ancestor — deepest (birthplace) first. Report
 * dedups that to the single birthplace record; live deliberately does not, showing the propagation trail.
 *
 * [info] is optional record-level metadata (ADR-010): it merges over the span's [dev.kotrace.Span.info] onto
 * the emitted [ExceptionRecord.info] **without** touching the [ExceptionEvent], which stays object-only (a
 * throwable, no attribute bag — ADR-005). A consumer uses it to carry an emit marker (an "explicit report"
 * flag, a user-report id) that kotrace does not interpret. It rides the *live* record only — the event holds
 * no info, so the report path (which rebuilds records from the event) is unaffected.
 *
 * This is the **public** verb: each call is its **own lineage** (a fresh [ExceptionEvent.lineageKey]), so an
 * exception a consumer records explicitly is never deduped against another (ADR-015). kotrace's own *climb*
 * re-recording goes through the internal [recordPropagatedException] instead, which stamps the canonical key.
 */
fun Span.addException(cause: Throwable, info: Map<String, String> = emptyMap()) {
    emit(ExceptionEvent(cause, System.nanoTime()), resolvedThreadConfig(), info)
}

/**
 * Records a **climbing** throwable (ADR-015): the internal counterpart of [addException] used by the [span]
 * catch and [dev.kotrace.end] as a failure propagates up the tree. It stamps the event with the *canonical*
 * [lineageKeyOf] the throwable, so the same failure re-recorded on every enclosing span shares one key and
 * report/render collapse it to its deepest birthplace — surviving the coroutine stacktrace-recovery copy that
 * makes the climbing object a different instance at each boundary.
 */
internal fun Span.recordPropagatedException(cause: Throwable) {
    emit(ExceptionEvent(cause, System.nanoTime()).stampLineage(lineageKeyOf(cause)), resolvedThreadConfig())
}

/** The birthplace throwable recorded on this span, if any — the first [ExceptionEvent] on its timeline. */
val Span.exception: Throwable?
    get() = events.firstNotNullOfOrNull { (it as? ExceptionEvent)?.throwable }