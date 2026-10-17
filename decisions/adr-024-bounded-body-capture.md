# ADR-024 — OkHttp `Level.BODY` is bounded and transport-neutral

- **Date:** 2026-09-25
- **Status:** Accepted
- **Affects:** `TracingInterceptor` at `Level.BODY` only — how much of a body it reads, when, and which bodies it
  skips. `bodyLimit` is now validated (`>= 0`). No API or wire change.
- **Builds on:** [ADR-023](adr-023-okhttp-trace-handoff.md) (instrumentation only observes).

## Context

`Level.BODY` is the debug dial a consumer turns on in place of OkHttp's `HttpLoggingInterceptor`. It changed the
transport and lost data:

1. **The response snippet was lazy.** The message lambda `{ response.peekBody(bodyLimit).string() }` ran only when
   an adapter accepted the event. For a report adapter that accepts sensitive events (the audience `BODY` exists
   for), that was at `reportTrace`, after the caller had closed the body: `peekBody` threw `closed` in the middle
   of the record sequence, and fault isolation (ADR-014) dropped the adapter's **whole** `onReport`. The event also
   pinned the `Response` until the report.
2. **`bodyLimit` did not bound the request read.** `writeTo(Buffer())` wrote the entire request body into memory,
   and the limit only applied when decoding. A 16 MiB upload at `bodyLimit = 1 KiB` wrote 32 MiB (snippet + send):
   an OOM risk on a device, and double I/O for a file body.
3. **A stream stalled the call.** When a live adapter accepted sensitive events, the peek blocked `intercept` until
   `bodyLimit` bytes or EOF. A server-sent-event response (headers in 49 ms) reached the caller only after
   1730 ms. Duplex bodies were not skipped either, only one-shot ones.

## Decision

*Revised the same day after review; the first version is described under "Options considered".*

- **Request:** a one-shot or duplex body is logged as omitted and never written. A body whose **declared length
  exceeds `bodyLimit`** is not written at all; only its size is logged. Okio stages a single large write, such as
  a `ByteArray` body, whole before any sink below it can stop it, so a cap alone did not bound memory: a 16 MiB
  `ByteArray` body still cost 32 MiB. Any other body (it fits, or its length is unknown) is written into a capped
  sink that keeps the first `bodyLimit + 1` bytes and then stops the write with a stackless control exception.
- **Response (tee-on-read):** nothing is read before the response is returned. The body is wrapped in a source
  that copies the first `bodyLimit + 1` bytes **as the caller's reads pull them**, and logs the snippet once, when
  the caller reaches EOF or closes the body. The log runs under the flow's captured config (ADR-023), from
  whatever thread the caller reads on. The event carries only the resulting `String`, and the other response
  lines capture `code` and `headers` values, not the `Response`. A `text/event-stream` response, or one to a
  duplex request, is logged as omitted and left unwrapped. Wrapping never fails the call: on a fault, the
  original response is returned.
- **Snippet shape:**
  - up to `bodyLimit` bytes as UTF-8;
  - followed by `… (truncated, N bytes)` when the body was longer (`N` is omitted when the length is unknown);
  - followed by `… (read partially)` when the caller provably stopped early, i.e. closed the body before EOF
    with fewer bytes seen than its declared length. A JSON converter that stops at the closing brace without
    reading EOF is not marked.

  An empty or unread body logs nothing. `bodyLimit = 0` disables body capture, and a negative `bodyLimit`
  fails at construction.

## Options considered

- **An eager, bounded `peekBody(bodyLimit + 1)` in `intercept` (the first version) — replaced.** A byte bound is
  not a time bound: the peek waits for `bodyLimit + 1` bytes or EOF. Any open-ended response not recognized by
  content type stalled the call. An NDJSON stream reached the caller after 7.3 s instead of immediately, and no
  content-type list can name every stream. Tee-on-read has no such failure mode. Its costs: the snippet lands on
  the already-ended HTTP span, from the caller's thread, and a body the caller never reads logs nothing. Both are
  acceptable for a debug breadcrumb.
- **Skip every unknown-length (chunked) response — rejected.** OkHttp's transparent gzip strips
  `Content-Length`, so most real responses have an unknown length, and `BODY` would log almost nothing.
- **Gate the capture on "some adapter accepts sensitive" — rejected.** Acceptance is per-adapter and per-phase
  (live now, report later). With the capture bounded, the cost of taking it unconditionally at `BODY` is small,
  and `BODY` is debug-only by contract.

## Consequences

- A debug report sink receives body snippets. Before this change, the first closed body cost it the whole report.
- Uploads at `BODY` cost at most `bodyLimit` of extra memory and I/O, not the body size, **except** an
  unknown-length body that writes one large chunk: okio stages that chunk whole before the cap can stop it.
  Omitting every unknown-length body instead would drop the snippet of every streamed and multipart upload, so
  this debug-only cost is documented rather than designed away.
- The response is handed to the caller untouched in timing. The snippet appears once the caller has read the
  body, so a report that runs after the caller consumed it (the normal Retrofit shape) includes it.
- A binary body still reads as garbled text. That is acceptable for a device-only, sensitive debug event.
