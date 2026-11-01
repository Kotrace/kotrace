package dev.kotrace

/**
 * The `ThreadLocal` mirror behind each context element ([SpanContext], [SpanCollector], [TraceConfig],
 * [ScopeContext]): what non-suspend code on a coroutine's thread reads. Each element's `updateThreadContext` is
 * [swap] and its `restoreThreadContext` is [restore], so the mirror contract is written once, here. A helper rather
 * than a shared superclass, because the elements are public and an internal superclass cannot be exposed.
 */
internal class ThreadMirror<T : Any> {

    private val local = ThreadLocal<T?>()

    /** The value mirrored onto this thread, or null. */
    fun get(): T? = local.get()

    /** Mirrors [next] onto this thread and returns the value it replaces, which [restore] puts back. */
    fun swap(next: T?): T? {
        val previous = local.get()
        local.set(next)
        return previous
    }

    /** Puts back the value a [swap] returned. */
    fun restore(previous: T?) {
        local.set(previous)
    }

    /** Runs [block] with [value] mirrored onto this thread, then puts back what was there. */
    fun <R> mirroring(value: T?, block: () -> R): R {
        val previous = swap(value)
        try {
            return block()
        } finally {
            restore(previous)
        }
    }
}
