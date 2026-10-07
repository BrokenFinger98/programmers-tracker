---
type: decision
project: programmers-tracker
tags: [storage, jsonl, identity, mcp]
author: BrokenFinger98
created: 2026-10-07
updated: 2026-10-07
sources: [raw/sessions/2026-10-06-the-history-that-folded.md]
---

# A record is its time and its bytes

## Context

`RecordHistory` resolved the append-only log as the newest line per capture key, so that a
`codePending` correction replaces its original. The key is a digest of the grading's frames,
and since #159 the live path records byte-identical gradings as separate lines. Every reader
then folded them back into one: lesson 131537 recorded 3 submits and 10 runs, and
`get_problem` answered 1 and 5 (#343).

## Options considered

1. **A new `recordId` field** written per grading, referenced by the correction. A schema
   change, and every line written before it needs a fallback anyway.
2. **`(ts, captureKey)` as the identity.** The correction is `record.copy(...)` in
   `CodeAttachment`, so it repeats both by construction, and `ts` is taken inside the single
   writer section, so two gradings cannot share it.
3. **Make the key unique** by mixing in the time. The key must survive replay of the raw log,
   which has no write time of its own ([[decisions/2026-08-11-a-grading-is-its-whole-session]]).

## Decision

Option 2. `RecordHistory.of` keys on `ts to captureKey`.

## Rationale

It needs no schema change and no migration, and it was measured to hold on the whole live log:
242 lines in 121 `(ts, captureKey)` groups, each one grading and its correction.

## Accepted costs

- Identity now rests on a write-time clock reading. Two gradings written in the same
  nanosecond would fold; the single writer makes that unreachable, and nothing checks it.
- A future correction that is *not* a `copy` of its record would silently stop superseding.
  `CodeAttachmentTest` pins the current shape on the production path: the appended correction
  decodes to its original's `ts` and capture key.
- **Replay still folds.** Reconciliation re-reads raw frames already on disk and keeps the
  capture-key dedup index, so two byte-identical gradings both still waiting in the raw
  directory after a crash become one record. That is #159's accepted cost and unchanged here; it
  is now the only place the key acts as an identity.
- Records already written are not rewritten — the fix is in the reader, so every existing
  log resolves correctly the moment the server restarts.

## Outcome

#343. Tests: three in `RecordHistoryTest` (identical bytes at different times survive; a
correction still supersedes; a correction appended after a later identical grading replaces only
its own), one through the store in `RecordQueryTest`, one on the production correction path in
`CodeAttachmentTest`; the history tests were seen failing with the old key. Live acceptance:
`get_problem 131537` answers 3 submits and 10 runs on the rebuilt container.
