package dev.kotrace

import kotlinx.coroutines.withContext

/**
 * Runs [block] under a **scope**: a correlation key above traces (`scope ⊇ trace ⊇ span`, ADR-010), such as a
 * session or journey id, which kotrace never interprets. Every record emitted inside carries [scopeId]: a span's
 * alongside its `trace_id`, a span-less emit's ([dev.kotrace.event.emitLog]) alone.
 *
 * A scope is not a span: it opens no tree, has no outcome, and reports nothing. It holds no config either, so the
 * adapters in force are unchanged. A nested scope replaces this one until it exits.
 *
 * @sample dev.kotrace.samples.ScopeSamples.scopeUsage
 */
suspend fun <T> withScope(scopeId: String, block: suspend () -> T): T =
    withContext(ScopeContext(scopeId)) { block() }
