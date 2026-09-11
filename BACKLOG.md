# Backlog — open bugs, tech debt, missing tests

The **one** living log of work that is known and not done. Found something and can't fix it now? It
goes here — not a comment, not a commit message, not your head.

Everything is `Bnn` (bug), `Dnn` (debt), or `Tnn` (test). Fixed items are deleted, not struck through;
git remembers. Ids are positional and reused as items are deleted — **do not cite a `Bnn` from source**
(a `Dnn` is safe to cite: debt is long-lived).

- What is **built** → [ARCHITECTURE.md](ARCHITECTURE.md)
- Why → [DECISIONS.md](DECISIONS.md)

---

## Open

### Bugs

_None._

### Debt

- **D01 — no span-granular export hook; adapter surface is event-flattened.** A `TraceAdapter` only ever
  receives `TraceRecord`s (one per reportable event), never a `Span`. So an adapter cannot read per-span
  `startNanos`/`endNanos`/duration/`status`, gets no span-ended signal on the live path (`onLive` fires
  while the span is open, outcome unknown), and never sees a span that produced no reportable event
  (`reportTrace`'s walk emits records per event). **Why now:** every current sink is crash/log, which is
  event-granular — none needs spans. **Cost:** no faithful exporter to a span-oriented backend (OTLP /
  Tempo / Jaeger) can be built on the public API; a consumer must either encode span timing through
  `putInfo` (fragile, and a span-without-events stays invisible) or fork. **Trigger to repay:** the first
  consumer that needs to export spans to a trace backend. **Likely shape:** a `ReportAdapter` variant
  receiving `Span` (or a `SpanRecord` carrying start/end/status + nested events), fed from the same
  `SpanCollector` walk. Related: ADR-002 (fan-out is the single filtering authority), ADR-005
  (birthplace), ADR-006 (`Span` terminal state).

- **D02 — no persistence; an in-flight trace does not survive process death.** `reportTrace` is
  in-process code that runs at trace end, and the `SpanCollector` holding the open tree lives only in the
  coroutine context (in-memory). If the process dies mid-trace (user swipes the app away, OS OOM-kill,
  force-stop, or a fatal crash), that code never runs and the tree is lost — the trace produces **no
  report**. Only live emits already fanned before death survive, and only if their sink persisted them
  off-process (e.g. a Crashlytics `recordException`/`log` written to disk). A cooperative
  `CancellationException` is **not** this case: the process is alive, so `observe` still fans
  `reportTrace(CANCELLED)`. **Why now:** an interrupted operation is generally not a failure (same basis
  as gating CANCELLED out of the crash sink), so losing its report is usually correct, not a defect.
  **Cost:** kotrace cannot answer "how many operations died in-flight across process death" — a start
  with no end is invisible; a consumer needing that must model it out-of-band (e.g. a paired
  start/end analytics `event`, the gap measured backend-side) rather than from the trace tree.
  **Trigger to repay:** a concrete need to observe in-flight-at-death traces, or attach a kotrace span
  tree to a platform fatal. **Likely shape (heavy):** a live adapter that journals span state to disk and
  replays on next launch (the OTel-agent persistent-queue model) — a large scope change to kotrace's
  in-process, in-memory report model. **Probably won't-do** until such a need is real; logged so the
  boundary is known, not silently assumed.

### Tests

_None._

---

The full-source code review of 2026-08-22 has been fully triaged: every bug fixed (see
[DECISIONS.md](DECISIONS.md) ADR-004…008), the terminal-state debt fixed (ADR-006), the KDoc-drift debt
fixed (ADR-007), the `renderTree` PII debt fixed (ADR-008), and the `ThreadContextElement`-mirror debt
resolved as **won't-do (YAGNI at three)** — re-open only when a fourth mirrored context element appears.
