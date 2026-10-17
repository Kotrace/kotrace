package dev.kotrace

/**
 * What a `returnedOutcome` mapper reports for a normally returned value at the auto-root (ADR-016): any
 * [TraceStatus], taken as given, plus orphan failures to attach.
 *
 * [attached] is **only for trace-level orphan failures**, throwables that are no span's birthplace (a saga's
 * suppressed rollbacks, ADR-012). A throwable a span already records reaches the report through the tree;
 * attaching it too would report it twice.
 */
data class TraceOutcome(val status: TraceStatus, val attached: List<Throwable> = emptyList())
