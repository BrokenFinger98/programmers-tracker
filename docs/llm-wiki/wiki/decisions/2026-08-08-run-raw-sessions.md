---
type: decision
project: programmers-tracker
tags: [data-model, records, recovery]
author: BrokenFinger98
created: 2026-08-08
updated: 2026-08-11
sources: [raw/sessions/2026-08-11-capture-defects-found-by-solving.md]
---

# Where a run's original frames live, and what `rawPath` says about it

## Context

Every grading opens a session file under `.ps/raw/` and appends each frame to it verbatim.
A **submit** then copies that file into `problems/<id>/attempts/NNN.raw.jsonl` and the
source is deleted; its record's `rawPath` names the copy.

A **run** does neither. `SettledCapture.movesRaw` is false for a run — deliberately, since
design §5.1 says a run creates no `attempts/NNN.*` files — so nothing copied it and nothing
retired it. Two consequences, both measured on the developer's machine (four `action: run`
files sitting in `.ps/raw` from two days of use):

1. `RawSessionReconciler` scans that directory on every boot, so it re-read and re-settled
   every run ever captured. The capture-key index dropped each as a duplicate, so nothing
   was corrupted — but the work grew without bound.
2. The record's `rawPath` carried the bare session id (`20260806T…-181951.jsonl`), which is
   resolved against the record repository and matches nothing there. Development-rules §2.4
   promises "the record points at that file"; for the run half of all captures it did not.

The decision is forced because two rules point in opposite directions. Design §5.1: a run
creates no attempt file. CLAUDE.md: discarding original messages is forbidden.

## Options considered

**A. Copy runs into the record repository too**, under a non-attempt path such as
`problems/<id>/runs/<timestamp>.raw.jsonl`. `rawPath` resolves, no schema change. But the
design says a run "gets pressed dozens of times while writing code", so this puts dozens of
files per problem into the user's repository — the inflation §5.1 exists to prevent, moved
from `attempts/` to a new directory.

**B. Delete the session once the record is durable.** Simplest, and `.ps/raw` stays clean.
Squarely violates the forbidden-list entry on discarding originals: a run is the *only*
source of the full `errorText`, and any field we did not parse would be gone for good.

**C. Set it aside outside the record repository**, under `.ps/raw/recorded/`, and let
`rawPath` be null.

## Decision

**C.** A run's session is moved to `.ps/raw/recorded/` once its record is durable, and
`SubmissionRecord.rawPath` becomes nullable, holding null for a run.

`.ps/` is the tool's own state directory and sits outside the record repository in both
supported layouts — Docker mounts it separately from `/records`, and a native run resolves
it against the working directory. So the frames survive without a single file entering
`ps-records`.

> ⚠️ **Amended 2026-08-10 by [[decisions/2026-08-10-state-beside-the-records]].** The
> paragraph above is no longer true of the layout: `.ps/` moved *inside* the record
> repository, where design §5.1 had always drawn it. What the decision was protecting is
> unchanged and is now protected differently — `RecordRepositoryIgnores` puts `.ps/` in the
> repository's `.gitignore` at startup, so no run file enters a commit and `ps-records` still
> does not grow by a run. The choice between options A, B and C is untouched; only the
> mechanism that keeps C's frames out of the history is.

`unprocessed()` keeps only direct children whose name parses as a session, and a directory
never does, so a sub-directory is enough to take a session off the work list.

## Rationale

- The original survives, which is the forbidden-list rule that has no exceptions.
- The record repository does not grow, which is what design §5.1 was protecting.
- Null is the honest statement. The alternative — a path that resolves to nothing — reads
  exactly like a file that was lost, and this repository has already decided once that a
  stand-in indistinguishable from a measurement is the worst outcome
  ([[concepts/assumption-vs-measurement]]).
- The boot cost stops growing, which was the observable symptom.

## Accepted costs

- **A schema change.** `rawPath` is nullable, so every consumer must handle null. Design
  §5.2 is amended in the same change rather than left to drift.
- **`.ps/raw/recorded/` grows without bound on local disk** — roughly 1 KB per run. Nothing
  prunes it, deliberately: "disk is cheap; lost data is unrecoverable" (development-rules
  §2.4). If it ever matters, pruning is a separate decision with its own evidence.
- **A run's frames are no longer reachable *from the record*.** Someone re-analysing has to
  know that `.ps/raw/recorded/` exists; the record no longer points the way. That is the
  price of not putting them in the repository, and it is why this ADR exists rather than a
  comment.

## Outcome

**2026-08-10 (#130): the same mechanism was missing from the reconciliation path.** This ADR
removed the growing boot cost from the live path — a session is retired as part of writing its
record. Retirement lives *inside* `RecordWriter.write`, and a replayed session whose record
already exists is dropped by capture key before the writer writes anything, so it retired
nothing and stayed on the work list. Observed on a running server: the same file reconciled on
every boot since 2026-08-06, reported `duplicates=1` each time and left where it was. A
duplicate is now set aside like any other handled session — the same `recorded/` directory,
for the same reason, so no second notion of "already handled" was invented.


Shipped 2026-08-08 with #99. The four stale sessions on the developer's machine were the
measurement that prompted it.

The same reasoning settled #107 the following change: a frame belonging to no grading — its
`start` missed by a reconnect or by a late `/watch` — is kept under `.ps/raw/orphans/<lessonId>.jsonl`
and never joins the work list, because without a `start` there is no action and no identity
to derive a record from. Frames carrying no grading facts at all (welcome, the subscription
confirmation) are still just dropped: they are protocol noise, not evidence.

Related: [[decisions/2026-08-05-write-serialization]] · [[decisions/2026-08-06-record-corrections-by-append]].
