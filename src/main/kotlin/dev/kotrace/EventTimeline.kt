package dev.kotrace

import dev.kotrace.event.ExceptionEvent
import dev.kotrace.event.NamedEvent
import dev.kotrace.event.SpanEvent

/**
 * A span's events, copy-on-write and bounded (ADR-029). A read takes the current array without a lock, so an
 * iterator and a [snapshot] are stable however many appends follow. Appends are serialized, so the cap is exact.
 *
 * Unlike a `CopyOnWriteArrayList`, an append past the cap discards one older event and appends in the same copy,
 * where a remove then an add would copy the whole timeline twice. The discarded event is the oldest
 * [NamedEvent] (already fanned out live, never reported), else the oldest log, else the oldest exception; the new
 * event is appended after the choice, so the newest one, the escaping exception, is always kept.
 *
 * As a [List] it is read-only: its mutators throw, so the [Span.events] view cannot be cast back into a writer.
 */
internal class EventTimeline : AbstractList<SpanEvent>(), RandomAccess {
    @Volatile private var events: Array<SpanEvent> = NO_EVENTS

    /** How many [NamedEvent]s [events] holds, so a log-only timeline never scans for one. Guarded by `this`. */
    private var namedCount = 0

    override val size: Int get() = events.size

    override fun get(index: Int): SpanEvent = events[index]

    /** Iterates the timeline as it stood when called. */
    override fun iterator(): Iterator<SpanEvent> = events.iterator()

    /** The timeline as it stands now, read-only and never changed by a later append. O(1): it shares the array. */
    fun snapshot(): List<SpanEvent> {
        val current = events
        return if (current.isEmpty()) emptyList() else Snapshot(current)
    }

    /** Appends [event], discarding one older event first when the timeline holds [maxEvents]. Whether it discarded. */
    fun append(event: SpanEvent, maxEvents: Int): Boolean = synchronized(this) {
        val current = events
        val evicting = current.size >= maxEvents
        val next: Array<SpanEvent?>
        if (evicting) {
            val victim = victimIndex(current)
            if (current[victim] is NamedEvent) namedCount--
            next = arrayOfNulls(current.size)
            System.arraycopy(current, 0, next, 0, victim)
            System.arraycopy(current, victim + 1, next, victim, current.size - victim - 1)
        } else {
            next = current.copyOf(current.size + 1)
        }
        next[next.size - 1] = event
        if (event is NamedEvent) namedCount++
        @Suppress("UNCHECKED_CAST")
        events = next as Array<SpanEvent>
        evicting
    }

    /** The oldest [NamedEvent], else the oldest log, else the oldest exception, in one pass. */
    private fun victimIndex(current: Array<SpanEvent>): Int {
        val lookForNamed = namedCount > 0
        var oldestLog = -1
        for (index in current.indices) {
            val candidate = current[index]
            if (candidate is NamedEvent) return index
            if (oldestLog < 0 && candidate !is ExceptionEvent) {
                if (!lookForNamed) return index
                oldestLog = index
            }
        }
        return if (oldestLog >= 0) oldestLog else 0
    }

    /** A read-only view of one array the timeline no longer writes to. */
    private class Snapshot(private val events: Array<SpanEvent>) : AbstractList<SpanEvent>(), RandomAccess {
        override val size: Int get() = events.size
        override fun get(index: Int): SpanEvent = events[index]
    }

    private companion object {
        val NO_EVENTS = emptyArray<SpanEvent>()
    }
}
