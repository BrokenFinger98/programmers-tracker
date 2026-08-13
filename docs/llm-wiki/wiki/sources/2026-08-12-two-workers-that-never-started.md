---
type: source
project: programmers-tracker
tags: [orchestration, failed-attempts, supervision]
created: 2026-08-13
updated: 2026-08-13
sources: [raw/sessions/2026-08-12-two-workers-that-never-started.md]
---

# 2026-08-12 session summary — the orchestration detour the build night left out

Recovered from the pre-compaction transcript snapshot during the 2026-08-13 ingest; the build
night's own page records what was built, not the twenty-eight minutes that failed first.

## Key claims

1. **A multi-line task spec is split by the worker's composer** — fragments submit, the remainder
   sits unsent, and the known bare-Enter fix now submits garbage. Task specs are one line or a
   file path. The dispatch layer said `ready` throughout: **state is what the dispatcher
   believes; the terminal is what is true.**
2. **The clean re-dispatch was delivered intact and never answered**, with nothing to diagnose.
   The cause was deliberately not guessed.
3. **Stopping rule**: after one diagnosed failure and one undiagnosable one, the third attempt is
   direct execution, not a third dispatch — the owner called it first.
4. **An over-claim was corrected on the spot**: sequencing was justified as "all six tasks stack",
   which was wrong (1/3/6 were independent); the real constraint was Task 2 needing Task 1's
   type. Retracted with the actual dependency table before proceeding.
5. A supervision checklist written while waiting — did the worker actually pass RED; does
   `guards.sh` pass, especially English-only — was never used that night and is kept in
   [[concepts/orchestrated-implementation]] for the next dispatch.
