# ADR-004 — `toJson` nests `attributes` / `info`, instead of spreading them as sibling keys

- **Date:** 2026-08-22
- **Status:** Accepted (implemented — `TraceRecord.toJson` renders nested objects)
- **Affects:** `dev.kotrace.event.TraceRecord.toJson`, its wire contract, `:demo` output, `ARCHITECTURE.md` §6

## Context

`TraceRecord.toJson()` is the machine egress for a JSON log pipeline (ELK/Loki). A record carries three
groups of keys: fixed **identity** (`trace_id`, `span_id`, `parent_span_id`, `operation`, `kind`, and the
per-kind `message` / `name` / `exception`), the event's own **`attributes`**, and the span's late
**`info`** (ADR-001). The first is a closed, reserved set; the latter two are open string→string maps whose
keys are chosen by the consumer.

The original renderer **spread `attributes` and `info` inline as sibling keys**, alongside identity. The
stated rationale was "a backend gets queryable columns." That shape has two defects:

1. **Key collision.** A map key equal to a reserved identity key (`operation`, `message`, `kind`, …) — or an
   `attributes` key that also exists in `info` — produced a **duplicate JSON key**. Duplicate keys are
   parser-undefined (first / last / reject). The only structural fix within the flat shape is a
   first-writer-wins dedupe, which trades a duplicate key for a **silently dropped value** — arguably worse:
   an attribute named `operation` simply vanishes, with no signal.
2. **Top-level mapping explosion.** Every distinct, consumer-controlled attribute key becomes a top-level
   Elasticsearch field, pushing against the default 1000-field mapping limit.

The "queryable columns" benefit that justified flattening is also weaker than assumed on modern backends: a
nested field is queried by its dotted/underscored path (`attributes.http_method`; Loki's `| json` yields
`attributes_http_method`), so nesting is not a query barrier.

## Decision

Render the two open maps **nested under their own objects** — `"attributes":{…}` and `"info":{…}` — while
identity keys stay top-level. An empty map is omitted (leaner line; a missing object reads as "none").

```json
{"trace_id":"abc","span_id":"def","parent_span_id":null,"operation":"repo","kind":"log",
 "message":"m","attributes":{"layer":"http"},"info":{"http.status":"200"}}
```

The collision class is now **unrepresentable**: a consumer-chosen key lives in its own namespace and can
never equal a reserved identity key or a key in the other map. No dedupe code, no dropped values, no
duplicate keys — by construction, not by a guard.

## Consequences

- **B1 (duplicate keys) is gone structurally**, and the earlier first-writer-wins dedupe — which would have
  silently dropped colliding attribute values — is removed.
- **PII posture unchanged:** `toJson` remains machine egress with no user data; the exception still renders
  its class name only (never `throwable.message`), per `Span`'s invariant.
- **Elasticsearch:** the nested objects map cleanly to the `flattened` field type — one field per object,
  no top-level mapping explosion — while staying queryable by dotted path.
- **Wire contract change.** Consumers reading a flat `http_method` must now read `attributes.http_method`
  (or `info.http_method`). Accepted because no consumer was locked to the flat shape at the time of the
  change (kotrace's `toJson` shape had no downstream dashboards depending on it yet). Had one existed, the
  fallback was namespaced-flat keys (`attr.` / `info.` prefixes) — rejected below.
- **Identity keys remain top-level columns**, so `operation` as the flat join key (`ARCHITECTURE.md` §2) is
  unaffected.

## Rejected alternatives

- **Flat + first-writer-wins dedupe.** Keeps the flat shape, but a reserved-name collision silently drops
  the consumer's attribute value — data loss with no signal. Rejected: trading a malformed line for lost
  data is not a fix.
- **Flat + namespace-prefixed keys** (`attr.http_method`, `info.http_method`). Removes collisions while
  staying mostly flat. Still a breaking wire change (every field renamed), and the `.`-prefixed keys are
  just a nested object spelled awkwardly. Rejected: if the wire breaks anyway, the honest nested object is
  cleaner and ES-`flattened`-friendly.
- **`kotlinx.serialization`.** Still rejected (ADR unchanged rationale): a zero-dependency core lib must not
  force the serialization runtime + plugin on every transitive consumer, and the domain (3 sealed kinds,
  string→string maps) is trivial enough to hand-roll — the only real risk, escaping, is contained.
