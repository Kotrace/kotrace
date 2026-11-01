package dev.kotrace

/**
 * What a [ReturnVerdictClassifier] decides about a span's normally returned value (ADR-020): [Success], or a
 * [Failure] that marks the span [SpanStatus.ERROR] and supplies the auto-root's default [TraceStatus.ERROR] verdict.
 * A failure whose throwable is a genuine cancellation of the span's own coroutine marks it [SpanStatus.CANCELLED]
 * instead (ADR-022/044).
 *
 * It decides the span's status on a normal return only: a thrown failure is classified by kotrace itself, and the
 * trace's verdict is [TraceStatus].
 *
 * A failure's [Failure.throwable] decides whether it is also recorded:
 * - non-null: it is recorded through the same exception-lineage path as a thrown failure (ADR-018), so a crash
 *   sink receives it. Pass the real, stable throwable the value carries, so the climb dedups to one birthplace.
 * - null: status only. No event and no `TraceRecord` is created, so a crash sink receives nothing: kotrace never
 *   invents a throwable, since there is no crash object, exception lineage, or safe domain symbol to make up.
 */
sealed interface ReturnVerdict {
    /** The value is not a failure; the span keeps its status. */
    data object Success : ReturnVerdict

    /** The value is a failure. A null [throwable] marks the status only (see [ReturnVerdict]). */
    data class Failure(val throwable: Throwable?) : ReturnVerdict
}

/**
 * Classifies a normally returned value of type [T] as [ReturnVerdict.Success] or [ReturnVerdict.Failure].
 *
 * Installed process-wide on [Kotrace] as a `ReturnVerdictClassifier<Any?>`, since it runs on every span whatever it
 * returns, and overridable per [span] as a `ReturnVerdictClassifier<T>` that sees the span's own return type. A
 * process-wide one also fits where a per-span one is expected: a function taking `Any?` accepts any `T`.
 */
typealias ReturnVerdictClassifier<T> = (T) -> ReturnVerdict
