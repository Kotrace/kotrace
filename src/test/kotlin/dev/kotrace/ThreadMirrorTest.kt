package dev.kotrace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.concurrent.thread

/**
 * Proves the [ThreadMirror] contract every context element relies on: a [ThreadMirror.swap] returns the value it
 * replaces, a [ThreadMirror.restore] of that value unwinds a nested swap exactly, [ThreadMirror.mirroring] restores
 * on every exit, and one thread's value never reaches another.
 */
class ThreadMirrorTest {

    @Test
    fun `a swap returns what it replaces and a restore unwinds nested swaps in order`() {
        val mirror = ThreadMirror<String>()
        assertNull("nothing is mirrored before the first swap", mirror.get())

        val beforeOuter = mirror.swap("outer")
        val beforeInner = mirror.swap("inner")
        assertEquals("the innermost swap is mirrored", "inner", mirror.get())

        mirror.restore(beforeInner)
        assertEquals("restoring the inner swap brings back the outer value", "outer", mirror.get())
        mirror.restore(beforeOuter)
        assertNull("restoring the outer swap clears the mirror", mirror.get())
    }

    @Test
    fun `mirroring a value restores the previous one after the block, even when it throws`() {
        val mirror = ThreadMirror<String>()
        mirror.swap("outer")

        assertEquals("inner", mirror.mirroring("inner") { mirror.get() })
        runCatching { mirror.mirroring("failing") { throw IllegalStateException("from the block") } }

        assertEquals("the outer value is back", "outer", mirror.get())
    }

    @Test
    fun `a value mirrored on one thread is not seen on another`() {
        val mirror = ThreadMirror<String>()
        mirror.swap("main")

        var seenElsewhere: String? = "unset"
        thread { seenElsewhere = mirror.get() }.join()

        assertNull("another thread sees no mirrored value", seenElsewhere)
        assertEquals("this thread keeps its own value", "main", mirror.get())
    }
}
