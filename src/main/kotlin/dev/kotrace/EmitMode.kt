package dev.kotrace

/**
 * When a span's log events reach a sink.
 *
 * - [OnFailure] — events accumulate on the span and fan out **only** when the trace fails, via
 *   [SpanCollector.report]. The production default: a successful trace emits nothing.
 * - [Live] — every event is emitted to [KotraceLog.liveSink] the moment it is logged, like a classic
 *   line-per-call HTTP logger. For debug builds that want to watch traffic as it happens.
 * - [Both] — live *and* the failure fan-out.
 *
 * Live emission is independent of the failure fan-out's PII rule: the live sink is a local debug sink
 * (Logcat), so it receives every event including `local` ones (captured bodies). Only [report] — the
 * remote path — drops `local` events.
 */
enum class EmitMode { OnFailure, Live, Both }
