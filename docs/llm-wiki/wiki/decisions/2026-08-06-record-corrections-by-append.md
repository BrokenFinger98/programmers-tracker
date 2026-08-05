---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [storage, jsonl, corrections, proposed]
created: 2026-08-06
updated: 2026-08-06
sources: [raw/sessions/2026-08-05-design-review-and-stack-upgrade.md]
---

# Records are corrected by appending, not by editing

Date: 2026-08-06 · Status: **proposed — awaiting the repository owner** · Issue: #36

> Raised by unattended work and deliberately left unmerged. It changes what
> `log/submissions.jsonl` means, which the design document describes, so it is the owner's
> call rather than an implementation detail settled overnight.

## Context

Stage 3 attaches the fetched code after a record is already durable, so it must clear
`codePending` on a line that has already been written. The log is append-only, and that is
not incidental: it is why the log can be the attempt authority and the dedup index
([[decisions/2026-08-05-write-serialization]]), and why a crash costs one record rather than
a file.

Design §5.1 currently describes the file as "every submission, one line each". Any mechanism
for corrections changes that sentence.

## Options considered

- **Rewrite the line in place** — rejected. Clearing one boolean would mean rewriting a file
  whose every line is an unrecoverable grading (protocol §11). The blast radius of a failed
  rewrite is the whole history; the value at stake is a flag.
- **A sidecar index of attachments** — rejected as a second source of truth for record state,
  the same shape of mistake the attempt counter already had before #18 fixed it.
- **Append a corrected record, newest-per-`captureKey` wins (chosen)** — the file stays
  append-only and the correction is itself a durable event.

## Decision

A correction is appended as a full record carrying the same `captureKey`. `RecordHistory`
resolves reads to the newest line per key, **kept in the position of that key's first line**
so chronology is preserved. The attempt counter still takes the maximum and the dedup index
is still a set, so neither is disturbed by a second line for the same key.

## Rationale

- The property that makes the log trustworthy — append-only — is preserved rather than
  excepted for a convenience.
- A correction is a fact about what we learned later, not an erasure of what we knew before;
  storing it as an event keeps both readable.

## Accepted costs

- **The file is no longer "one line per submission"**, and design §5.1 says it is. Every
  consumer that reads the JSONL directly — MCP tools, Obsidian Dataview queries, anything a
  user writes — must resolve newest-per-key or silently double-count. That is the real cost
  and the reason this is proposed rather than assumed.
- The log grows by one line per attachment.
- A reader that stops at the first matching key sees stale state, which is a subtle failure
  mode rather than a loud one.

## Outcome

Pending. If accepted, design §5.1 and §5.2 need the correction semantics written into them,
and the Obsidian query templates need checking before they are shipped.
