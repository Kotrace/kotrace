# kotrace: house rules

The rules you must follow when writing code here. **This file is not an explanation of the
architecture**: it is the list of things review will hold you to.

| I need… | Go to |
|---|---|
| the module map, build and test commands | [AGENTS.md](AGENTS.md), imported below |
| how the pieces fit together, and why | [ARCHITECTURE.md](ARCHITECTURE.md) |
| to use the library, or to publish it | [README.md](README.md) |
| why a decision was made | [DECISIONS.md](DECISIONS.md): read the index, open one ADR |
| what is broken or missing | [BACKLOG.md](BACKLOG.md) |
| what each adapter receives in a scenario | [docs/outcome-matrix.md](docs/outcome-matrix.md) |
| what changed for consumers | [CHANGELOG.md](CHANGELOG.md) |

**Coordinates:** `dev.kotrace:*` · **Kotlin only, no Java** · Java 11 bytecode · consumer floors in the catalog
(ADR-027).

@AGENTS.md

---

## The invariants

Break one of these and a test fails. They are not style: they are the contract
([ARCHITECTURE.md](ARCHITECTURE.md) §11 states each with its ADR).

- **Tracing never changes the traced code's result.** `span { }` returns or throws exactly what its block would
  untraced, a fault of kotrace's own included. Instrumentation observes and rethrows; it never swallows.
- **An adapter's fault stays with that adapter.** It reaches the process-wide `FaultHook`, never the traced code
  or another adapter. Fault isolation in the core and in every integration goes through `runIsolated`
  (`bridge/`, ADR-035); an integration does not write its own.
- **Fail-closed by default.** An adapter receives no sensitive log and no attribute it has not accepted.
- **`acceptsSpan` reads `traits` only**, fixed when the span opens.
- **Memory is bounded per span** (`maxEventsPerSpan`).
- **A genuine cancellation records no exception.**
- **Unredacted output never reaches a sink.** `renderTree()` stays behind `@UnredactedTraceRead`.
- **The module split holds.** The core is pure Kotlin plus coroutines; OkHttp and Android/Room live only in their
  modules; the core never names a JVM-only or library-specific concept (a `FaultPhase` is a domain: `HTTP`,
  `DATABASE`), because KMP and Ktor are planned consumers.

---

## Conventions

### API

- **One public verb per concern; keep the surface minimal.** An integration reuses what the core provides
  rather than adding a module-local helper.
- A public API change updates the `api/*.api` dump in the same commit. A binary break joins the unreleased
  version in `gradle.properties` (a published version is immutable), with an ADR, a `CHANGELOG.md` entry and a
  README upgrade note.
- A name, a parameter name included, does not collide with another API name.
- Spans carry **static symbols only**, never user data: a span can reach a crash report or the wire.
- Wire fields are snake_case JSON and W3C `traceparent`. A wire change is deliberate and documented.

### Vocabulary

The tracing terms (`trace`, `span`, `scope`, `link`, `operation`, `record`, `log`, `event`, `report`, `live`,
`adapter`) are defined once, in
[ARCHITECTURE.md](ARCHITECTURE.md) § Vocabulary. Use them as defined; never interchange them. Consumers such as
Camailux reference this glossary rather than redefining it, so a change to a term is a change for them too.

### Writing

These hold for every comment, KDoc, string, test name and Markdown file.

- No abbreviations. Never the em dash as a connector: use a colon, a semicolon, a comma or a new sentence.
- A class's field and property docs go in the class KDoc, not above each field.
- Living docs (README, ARCHITECTURE, AGENTS, this file, BACKLOG, the DECISIONS index, `docs/`) describe the code
  as it is: no history ("formerly", "renamed from", "since ADR-x"). ADR files stay decision records; keep only
  their status current. The README "Upgrading from 0.x" notes stay.
- A behavior change updates the troubleshooting row and the ARCHITECTURE §11 entry it moves.

### Tests

- Every public function ships with a test. Plain JUnit4. A test doubles as a usage example.
- Tests mirror the source packages.
- A coroutine test may receive a stacktrace-recovery copy of a thrown exception: compare through the cause chain,
  not `assertSame`.

### Decisions

- A new decision is an ADR in `decisions/` plus a row in the [DECISIONS.md](DECISIONS.md) index. A change to a
  concept extends its chain there; read the last ADR of the chain before changing behavior it covers.
- Found a bug while doing something else? It goes in [BACKLOG.md](BACKLOG.md): do not fix it inline and do not
  leave it in a comment. Cite a `Dnn` from source if needed, never a `Bnn`.

---

## Working with Claude

- **Explain, then offer options.** For a design question, explain the mechanism, give two to four options with a
  recommendation and the honest trade-offs. Verify a claim against the code before asserting it.
- Ask before implementing anything complex or ambiguous. Break work into small, independently verifiable steps.
- Observability is proactive: add fault reporting where data would be lost, without waiting for the symptom.
- Commit and push only when asked. Keep history to a few themed commits, each building; rewrite only unpushed
  history.
- Camailux (`../Camailux/android`, the binding in `platform-sdk`, module `core/trace/kotrace`) is the reference
  consumer. Change it only when asked. "Camailux does not use X" does not mean X is unneeded.
