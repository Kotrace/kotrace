package dev.kotrace

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal val collectorMirror = ThreadMirror<SpanCollector>()

/**
 * The [SpanCollector] in the current coroutine, or null. Non-suspend code reads [currentThreadCollector] instead.
 */
suspend fun currentCollector(): SpanCollector? = currentCoroutineContext()[SpanCollector]

/**
 * The [SpanCollector] mirrored onto this thread, or null: for **non-suspend** code running on a coroutine's thread
 * (the OkHttp `Call.Factory`). A suspend caller should prefer [currentCollector].
 */
fun currentThreadCollector(): SpanCollector? = collectorMirror.get()

/**
 * Holds every [Span] of a trace until it is reported, so the whole tree can be walked. Seeded at the root; each
 * span registers itself as it opens, concurrently for parallel children. As a [ThreadContextElement] it mirrors
 * itself onto the thread for [currentThreadCollector].
 *
 * [spans] is a snapshot safe to iterate during concurrent registration. Attached failures are held separately,
 * unique by identity and bounded at [MAX_ATTACHED_FAILURES] for this collector (ADR-045). Their registry is created
 * on the first registration, so a collector that never attaches holds none and a report reads one null.
 */
class SpanCollector :
    ThreadContextElement<SpanCollector?>, AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<SpanCollector>

    private val entries = CopyOnWriteArrayList<Span>()
    private val attachmentLock = Any()
    @Volatile private var attachedRegistry: AttachedRegistry? = null

    val spans: List<Span> get() = entries.toList()

    internal fun add(span: Span) {
        entries.add(span)
    }

    internal fun attach(owner: Span, cause: Throwable, atNanos: Long) {
        synchronized(attachmentLock) {
            if (entries.none { it === owner }) return
            val registry = attachedRegistry ?: AttachedRegistry().also { attachedRegistry = it }
            if (cause in registry.identities) return
            if (registry.failures.size == MAX_ATTACHED_FAILURES) {
                val dropped = owner.attributes[Span.ATTACHED_DROPPED_ATTRIBUTE]?.toLongOrNull() ?: 0L
                owner.putAttribute(Span.ATTACHED_DROPPED_ATTRIBUTE, (dropped + 1L).toString())
                return
            }
            registry.failures += AttachedFailure(owner, cause, atNanos)
            registry.identities += cause
        }
    }

    internal fun attachedSnapshot(): List<AttachedFailure> {
        val registry = attachedRegistry ?: return emptyList()
        return synchronized(attachmentLock) { Collections.unmodifiableList(registry.failures.toList()) }
    }

    override fun updateThreadContext(context: CoroutineContext): SpanCollector? = collectorMirror.swap(this)

    override fun restoreThreadContext(context: CoroutineContext, oldState: SpanCollector?) = collectorMirror.restore(oldState)
}

/** The most attached failures one collector holds (ADR-045); a further distinct registration is refused and counted. */
internal const val MAX_ATTACHED_FAILURES: Int = 100

/** A registered [throwable], owned by [owner], with its registration time [atNanos]. */
internal class AttachedFailure(val owner: Span, val throwable: Throwable, val atNanos: Long)

/** Ordered [failures] and their [identities], both guarded by the collector's attachment lock. */
private class AttachedRegistry {
    val failures = ArrayList<AttachedFailure>()
    val identities: MutableSet<Throwable> = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
}
