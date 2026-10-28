# ADR-039: An adapter receives no attribute it has not accepted

- **Date:** 2026-10-26
- **Status:** Accepted (implemented in 0.6.0; see [Implementation](#implementation))
- **Affects:**
  - `TracePolicy` gains `fun acceptsAttribute(key: String, value: String): Boolean = false`;
  - every record handed to an adapter, live or report, carries only the attributes its policy accepts: the record's
    own attributes, `spanAttributes` and link attributes;
  - the keys kotrace writes itself become public constants (`Span.EVENTS_DROPPED_ATTRIBUTE` already is; the
    `:kotrace-okhttp` `HttpSpan` keys are `internal` today).

  **Behaviour-breaking:** with the default, an existing adapter stops receiving attributes until its policy accepts
  them. Lands in 0.6.0 beside ADR-038, with the `api/kotrace.api` and `kotrace-okhttp/api/kotrace-okhttp.api` dumps
  updated. The API change itself is additive (a defaulted interface member and a newly public object), so it is
  source- and binary-compatible.
- **Amends:** [ADR-021](adr-021-http-span-name-no-url-path.md), whose context notes that only `LogEvent.message` has a
  gate.

## Context

kotrace's PII gate is `sensitive` plus `TracePolicy.acceptsSensitive`, and it covers one field: `LogEvent.message`.
Attributes have no gate. They reach every adapter on both the live and the report path, held to "a symbol, never user
data" by KDoc alone.

Adapters need different answers. A crash or remote-log adapter must ship only a vetted vocabulary; a debug logcat
adapter wants every attribute, on a device the developer holds. kotrace cannot know which keys are safe: that
vocabulary is the consumer's, as a route template is (ADR-021).

## Decision

- **Per adapter, deny by default.** `acceptsAttribute` defaults to `false`, as `acceptsSensitive` does: an adapter
  that declares nothing receives no attribute. Admitting a key is an explicit act in the adapter's policy.
- **The consumer writes the policy; kotrace applies it.** kotrace never interprets a key. An allowlist, a value shape,
  or `true` for a debug adapter are all the consumer's predicate.
- **Applied when the adapter's record is built**, in the same walk that applies `acceptsEvent` and `acceptsSensitive`
  ("one walk, N views"). An adapter's records, and any `toJson` of them, carry only what it accepted. The span keeps
  every attribute, so a permissive adapter still sees them.
- **A rejection is policy, not a fault.** It is not reported, as a refused sensitive log is not. A throwing
  `acceptsAttribute` is contained like any throwing policy (ADR-014), and that attribute is rejected.
- **No exemption for kotrace's own keys.** They are published as constants and admitted like any other. A bridge key
  is caller-derived in principle (ADR-021's path), so it earns no blanket pass.

**Out of scope:** the span name (`operation`, ADR-021), `NamedEvent.name`, `LogEvent.message` (already `sensitive`)
and the throwable's message. Traits never reach a record (ADR-001). `renderTree` reads the span itself and stays behind
`@UnredactedTraceRead` (ADR-008).

## Options considered

- **One process-wide filter at capture: rejected.** It cannot let a debug adapter see what a release one must not; a
  rejected attribute would be gone for every adapter.
- **Default `true` (opt out): rejected.** An adapter that forgets would leak; the default must be the safe one, as for
  `acceptsSensitive`.
- **kotrace ships an allowlist of known keys: rejected.** It cannot know a consumer's vocabulary.
- **A structured allowlist (`Map<String, (String) -> Boolean>`) instead of a predicate: rejected.** It forces the
  allowlist shape but not its content (a `{ true }` value check still passes anything), and costs a rigid API.

## Consequences

- An adapter sees exactly the attributes it admitted. A debug adapter opts into all of them with one line.
- A record delivered to adapters with different policies is copied once per differing view, on the live path too;
  an adapter accepting everything, or a record with no attributes, needs no copy. The predicate runs per attribute per
  adapter, a hot path (ADR-029): it must be cheap, non-blocking and free of catastrophic backtracking.
- Every existing adapter, including the samples and `:demo`, must declare what it accepts.

## Implementation

Implemented as decided; the points below record what the code settled and where the text above needed precision.

- **One view function, three fan-out sites.** An internal `TracePolicy.attributeView(record, phase, faultMessage)`
  filters a built record's `spanAttributes`, its event `attributes` (`LogRecord`, `NamedRecord`) and each link's
  `attributes`. It runs inside each adapter's delivery guard on the live path (`Emit.kt`), the span-less path
  (`SpanlessEmit.kt`), and as the last step of the report `viewOf` (`Report.kt`).
- **Copies.** Each attribute map is copied only on its first rejection, and the record only when one of its maps
  changed; a fully rejected map becomes an empty one. The live record is built once and shared as before, so an
  adapter accepting everything, or a record with no attribute, receives the shared instance. On the report path each
  adapter's records were already built per adapter (ADR-019), so a differing view costs one further copy per
  record there, not one per adapter.
- **A throwing predicate** is contained per attribute and reaches the `FaultHook` with a step of its own:
  `live.attribute`, `spanless.attribute` or `report.attribute` (ADR-036). The record is still delivered.
- **`acceptsEvent` still sees the event's own attributes, unfiltered.** The gates decide delivery and read the event
  (a `level` threshold keeps working for an adapter that admits no `level`); `acceptsAttribute` decides only what the
  delivered record shows. This keeps ADR-001's invariant: no late value decides whether a record is delivered, and a
  live record missing an attribute stamped later is unchanged behaviour.
- **Published keys.** `:kotrace-okhttp`'s `HttpSpan` is public with `STATUS` and `OUTCOME`, and also the two
  `OUTCOME` values `CALL_TIMEOUT` and `CANCELED`, so a value-shape predicate can name them too. `:kotrace-room` writes
  no key of its own: its `level` attribute is the caller's.
- **Shipped adapters declare their vocabulary:** `:demo`'s export admits a named set (its live watch accepts all),
  the README and its compiled snippets show an allowlist and a debug policy, and `src/samples` gains
  `PolicySamples` for the `acceptsAttribute` KDoc. Tests that read attributes off records now declare that they
  accept them; `AttributePolicyTest` covers the gate.
