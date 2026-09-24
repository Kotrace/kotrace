package dev.kotrace

/**
 * A failure recognized from a span's normally returned value (ADR-020).
 *
 * [ValueOnly] marks the span [SpanStatus.ERROR] and can supply the auto-root's default
 * [TraceStatus.ERROR] verdict, but deliberately emits no event: without a [Throwable] there is no crash
 * object, exception lineage, or safe domain symbol for core to invent. [CausedBy] additionally records the
 * supplied throwable through the same exception-lineage path as a thrown failure (ADR-018).
 */
sealed interface ReturnedFailure {
    /** A returned failure value with no throwable. Produces status only; no `TraceRecord` is created. */
    data object ValueOnly : ReturnedFailure

    /** A returned failure carrying the real, stable [throwable] that should become its exception lineage. */
    data class CausedBy(val throwable: Throwable) : ReturnedFailure
}

/**
 * Classifies a normally returned value as success (`null`), a status-only failure
 * ([ReturnedFailure.ValueOnly]), or a throwable-backed failure ([ReturnedFailure.CausedBy]). Installed
 * process-wide on [Kotrace] and overridable per [span].
 */
typealias FailureClassifier = (Any?) -> ReturnedFailure?
