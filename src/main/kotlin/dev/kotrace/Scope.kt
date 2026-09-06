package dev.kotrace

import kotlinx.coroutines.withContext

/**
 * Establishes an ambient **scope** — a live-only correlation umbrella above a trace (`scope ⊇ trace ⊇
 * span`, ADR-010) — for the duration of [block], and returns its result. A scope is **not** a span: it
 * opens no reportable tree, registers no [SpanCollector], has no duration or outcome, and never fans a
 * [reportTrace]. It only propagates [scopeId] via [ScopeContext].
 *
 * It does **not** carry fan-out config: the config is process-wide ([Kotrace]) or a per-flow context
 * override, resolved at the emit site ([dev.kotrace.resolvedThreadConfig]), so an operation inside a scope
 * reaches the consumer's adapters without the scope holding them. A scope opened inside an existing trace
 * keeps that trace's context override untouched.
 *
 * Inside the scope:
 * - a [span] / [reportTrace] opened here stamps [scopeId] onto its records alongside `trace_id`;
 * - a span-less emit ([dev.kotrace.event.emitLog] / [dev.kotrace.event.emitNamed] /
 *   [dev.kotrace.event.emitException]) carries [scopeId] alone — nothing is truly orphan while a scope is
 *   active.
 *
 * Nested scopes do not stack: the innermost [scopeId] wins and the outer is restored on exit (ADR-010).
 *
 * [scopeId] is a caller-chosen correlation key — a session id, a journey id, "handling push X". kotrace
 * never interprets it and never subjects it to any trace-outcome logic.
 *
 * @sample dev.kotrace.samples.ScopeSamples.scopeUsage
 */
suspend fun <T> withScope(scopeId: String, block: suspend () -> T): T =
    withContext(ScopeContext(scopeId)) { block() }
