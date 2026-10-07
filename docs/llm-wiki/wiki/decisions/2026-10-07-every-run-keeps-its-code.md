---
type: decision
project: programmers-tracker
tags: [storage, code, measurement, mcp]
author: BrokenFinger98
created: 2026-10-07
updated: 2026-10-07
sources: [raw/sessions/2026-10-06-the-history-that-folded.md]
---

# Every run keeps its code

## Context

[[decisions/2026-10-07-mistake-patterns-are-diagnosed-not-stored]] needs the correction between a
failed grading and the next attempt, and a run kept no code: `Solution.<ext>` is overwritten by
the next one. Lesson 273711's eleven runs left none.

## Options considered

1. **A file per run** (`runs/NNN.<ext>`) — readable in the vault, dozens per problem.
2. **One `runs.jsonl` per problem**, a line per run with full code — chosen.
3. **Diffs instead of code** — smaller, but a pending run attached late by the startup retry
   arrives out of order and a diff would be against the wrong neighbour.
4. **Copy the verdict into the line** — rejected: a second authority beside the log, which would
   disagree the day a classification rule changes, as #350 just did.

## Decision

Option 2: one `problems/<id>/runs.jsonl`, written by the store when a run's code is attached. A
line has four keys — `recordId`, `language`, `codeFetchedAt`, `code` — and is idempotent by
`recordId`, checking complete lines only so a crash-torn line cannot block the retry. Verdicts are
joined from `log/submissions.jsonl` by `recordId`. Committed with the records as everything else
in the problem directory is.

## Rationale

Code is small (an SQL run ~200 B). One file keeps the vault readable and gives part 4.3 a single
place to read. Measured 2026-10-07 10:29 on lesson 59035: four runs alternated as fast as a human
can, the code landed 0.24–0.53 s after each record, and every run got its own query.

## Accepted costs

- **A late attachment gets the page's newest code**, possibly a later run's. A reader detects it
  by `codeFetchedAt` later than the next record's `ts` (when that grading was recorded).
- **The ~0.3 s race may not be detectable.** A second Run pressed within the fetch window and
  finishing after the fetch slips past that check; the capture handles frames in order, so the
  next `start` is not visible at attach time. The fastest measured human gap was 1.6 s.
- Every run's code is published with the records (the owner's decision, 2026-10-07).
- Runs recorded before this have no line.

## Outcome

#351. Live acceptance pending: a problem run several times leaves one line per run with its own code.
