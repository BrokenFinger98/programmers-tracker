---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [storage, jsonl, idempotency, codefetch, append-only]
created: 2026-08-05
updated: 2026-08-05
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md]
---

# Clearing `codePending` by appending a correction, not by editing the line

Date: 2026-08-05 · Status: accepted · Issue: #36

## Context

Stage 3 attaches the fetched code to a record that stage 2 already wrote
([[decisions/2026-08-05-capture-pipeline-stages]]). Three fields change when it succeeds —
`codePath`, `diffFromPrev` and `codePending` — and the record they belong to is already a
line in `log/submissions.jsonl`.

That file is append-only, and the property is load-bearing rather than incidental:
[[decisions/2026-08-05-write-serialization]] makes it the single authority for `attempt`
(decision 2) and the home of the dedup capture key (decision 6). Both indexes are restored
from it at startup. So "update a record" has no obvious move.

## Options considered

- **Rewrite the line in place.** The corrected JSON is longer than what it replaces, so this
  is a whole-file rewrite. A crash halfway through puts *every* verdict in the log at risk in
  order to clear one boolean on one record — spending the unrecoverable artifact on the
  recoverable one, which is the exact inversion the three-stage pipeline exists to undo. The
  log also has no atomic-replace path today; `AtomicStateFile` covers the read-modify-write
  state files, not the append log.
- **A sidecar index** (`log/attached.jsonl`, or a `codePending` set). Record state then lives
  in two files that can disagree, and every reader has to consult both. Decisions 2 and 5 of
  the write-serialization ADR delete second sources rather than guard them; adding one back
  for a boolean contradicts the posture that produced them.
- **Append a corrected copy of the record (chosen).** The file stays append-only. Readers
  resolve a capture key to its newest line.

## Decision

Stage 3 appends a **complete corrected record** carrying the same `captureKey`, and
`application/RecordHistory` is the one place that resolves stored lines to record state: the
newest line for a capture key wins, **in the position the first line held**.

Every consumer that wants records rather than raw lines goes through it — the README
generator, the pending-retry pass, and anything later (MCP, Obsidian queries).

## Rationale

The correction repeats the record's `lessonId`, `action`, `attempt` and `captureKey`, which
is what makes it invisible to both in-memory indexes `RecordWriter` restores at startup:

- **The attempt counter** takes the *highest* number per problem (`AttemptAuthority.from`), so
  a repeat of a number already seen cannot raise it. Pinned by
  `CodeAttachmentTest.the correction repeats the attempt number, so the counter cannot move`.
- **The dedup index** is a `Set<CaptureKey>`, so a repeat collapses into the entry already
  there — and because the key is unchanged, a raw-log replay of the same grading is still
  dropped. Pinned end to end by
  `CodeAttachmentTest.a replay is still dropped after the correction`.

Keeping the *first* line's position matters for a reason that is easy to miss: an attachment
can land minutes after the record, and by then another attempt may have been written. Ordering
by the correction's arrival would shuffle a problem's attempt history in its README.

## Accepted costs

- The log grows by one line per successful attachment — roughly double its final size, since
  almost every record ends up attached. Negligible at one human's solving rate, real on disk.
- No reader may take a raw line at face value again. A consumer that skips `RecordHistory`
  sees a stale `codePending: true` and is not obviously wrong while doing so, which is the
  kind of trap that surfaces months later.
- Duplicate lines make the log harder to read by eye, and a `grep` over it now over-counts.
- The correction is not atomic with the files it names: a crash between the artifact write and
  the append leaves the files on disk with the record still pending. The retry then rewrites
  the same bytes, so this costs work rather than correctness.

## Outcome

Implemented in #36 together with the wiring of stage 3. Related:
[[decisions/2026-08-05-capture-pipeline-stages]] · [[decisions/2026-08-05-write-serialization]].
