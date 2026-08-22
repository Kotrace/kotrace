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

_None._

### Tests

_None._

---

The full-source code review of 2026-08-22 has been fully triaged: every bug fixed (see
[DECISIONS.md](DECISIONS.md) ADR-004…008), the terminal-state debt fixed (ADR-006), the KDoc-drift debt
fixed (ADR-007), the `renderTree` PII debt fixed (ADR-008), and the `ThreadContextElement`-mirror debt
resolved as **won't-do (YAGNI at three)** — re-open only when a fourth mirrored context element appears.
