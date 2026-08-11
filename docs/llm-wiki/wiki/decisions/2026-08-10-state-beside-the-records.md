---
type: decision
project: programmers-tracker
tags: [storage, configuration, git, credentials, deployment]
author: BrokenFinger98
created: 2026-08-10
updated: 2026-08-11
sources: [decisions/2026-08-05-write-serialization, decisions/2026-08-06-record-repository-lock, concepts/assumption-vs-measurement, raw/sessions/2026-08-11-capture-defects-found-by-solving.md, raw/sessions/2026-08-10-sensor-verified.md]
---

# State lives beside the records, except the two things that are credentials

## Context

Design §5.1 draws `.ps/` inside the record repository. Every `under(recordRoot)` factory —
`AtomicStateFile`, `FileProblemTimer`, `FileBackupLog`, `FileRawSessionLog` — was written for
that, and `FileRawSessionLog`'s own KDoc asserts it: "`.ps/raw` in the record repository".

Production called none of them. `CaptureConfiguration` and `GitConfiguration` built the three
from CWD-relative properties (`tracker.raw-dir`, `tracker.timers-file`,
`tracker.backup.state-file`), so state landed wherever the process happened to start.
Measured on the developer's machine: the record repository held no `.ps/` at all, while the
project checkout held four raw frame files, five running timers and the backup marker.

Under Docker nothing shows — the working directory is a mount that persists. Natively it is a
trap: start the server from another directory and it comes up with an empty raw queue, and a
grading Programmers has already broadcast cannot be replayed (protocol §11).

Those three properties also used `Path.of` where every other consumer of a configured path
used `ConfiguredPath`, so the shipped default `~/ps-records` would have produced a directory
literally named `~`.

## Options considered

1. **Leave it and document the working directory as significant.** Rejected: the design, four
   factories and a KDoc already say otherwise, and the cheapest way to make a document true
   is usually to make the code do what it says.
2. **Nest the property defaults** — `raw-dir: ${TRACKER_RAW_DIR:${tracker.record-repo}/.ps/raw}`.
   Rejected: it keeps three settings whose only correct values are derived, and Spring's
   placeholder nesting would still have gone through `Path.of`, so the tilde stays broken.
3. **Derive from `tracker.record-repo` and delete the properties.** Chosen.

## Decision

`raw/`, `timers.json` and `backup.json` resolve under `tracker.record-repo` through the
existing `under(recordRoot)` factories and `ConfiguredPath`. The three properties are
removed rather than kept as overrides: a raw-frame queue that can be pointed somewhere other
than the records it is a queue *for* has no correct second value, only wrong ones.

**`session` and `watch-token` deliberately do not move.** They are credentials and the record
repository is pushed. The split is now written next to both of them, because "state lives with
the records" is the kind of rule someone tidies into "all of it does".

**The server adds `.ps/` to the record repository's `.gitignore` at startup**
(`RecordRepositoryIgnores`). This is not tidiness. Reconciliation is `git add --all`, and
`.ps/raw/recorded/` is the directory [[decisions/2026-08-08-run-raw-sessions]] exists to keep
out of a commit: one file per **run**, and a run "gets pressed dozens of times while writing
code". That ADR weighed putting them in the repository and rejected it as the inflation §5.1
prevents; ignoring the directory is what carries that decision now the state is inside the
repository at all.

The template has carried the rule since #122, but a template is copied once, at creation:
repositories made earlier name `.ps/session` and `.ps/catalog.json` one at a time and ignore
none of the rest. Their
`.gitignore` belongs to the user, so one line is appended and nothing else is touched. It
runs on every boot, appends at most once, and logs rather than throws — losing a capture to a
`.gitignore` that would not write is the wrong trade in every direction.

## Rationale

The relocation closes a hole that had nothing to do with tidiness. `RecordRepositoryLock`'s
KDoc carried a paragraph titled "What it does not cover", and what it did not cover was
exactly this:

> `.ps/` (raw frames, timers, the backup marker) is shared between a container and a native
> run just as the records are, and is not the record repository. Nothing here locks it.

Two instances sharing a raw-frame queue and a timer document was unguarded. Moving the state
inside the repository puts it under a lock that already existed, so the fix is a consequence
rather than new machinery.

## Accepted costs

- **A `.gitignore` the server edits.** It is the user's file and we write to it. Mitigated by
  writing one line, only when absent, with a comment saying who added it and why — but a tool
  that edits your repository is a thing to be uneasy about, and this is the only place it does.
- **The state directory now travels with the records.** Copy a record repository to another
  machine and a stale raw queue and stale timers come along. Before, they stayed behind. The
  queue is idempotent and the timers only affect one `elapsedSec`, so this is cheap — but it
  is a real behaviour change, not a neutral one.
- **Three settings are gone.** Anyone who set `TRACKER_RAW_DIR` and friends is silently
  ignored rather than warned. Acceptable only because the repository has no releases, forks
  or stars — a fact that stops being true exactly once.
- **The credential split is a convention, not a mechanism.** Nothing stops a future change
  from moving `watch-token` in with the rest; only the comments beside it argue against.

## Outcome

⚠️ **Corrected 2026-08-10 (#128).** The paragraph above first justified the ignore rule by
claiming `.ps/raw/recorded/` duplicates every `attempts/00N.raw.jsonl`. It is the reverse:
`RecordWriter.retireRaw` discards a submit's source once the frames are in the attempt file
and sets aside only a run, so `recorded/` holds runs and nothing else. The rule is right and
the reason was wrong — and the wrong reason had been written by the server into a file in the
user's own repository, which is the failure this project calls its worst outcome. The real
justification was available in [[decisions/2026-08-08-run-raw-sessions]] the whole time, and
`RecordWriterTest` now pins both sides so it cannot drift again.

Verified against real git rather than argued: `RecordWriterGitTest` now places raw frames
where production does and runs the startup rule, so its existing "what does a commit carry"
assertions became the proof. Reconciliation's `git add --all` commits `.gitignore`,
`log/submissions.jsonl` and the attempt file — and nothing under `.ps/`.

The developer's machine was migrated by hand — there is no code for it, per
[[concepts/assumption-vs-measurement]] on not building for users who do not exist. Before the
first boot `git check-ignore` reported the state directory as committable; after it, the
repository was clean and the server's log line named the file it had edited. The one raw
session left on the work list was recognised as already recorded (`duplicates=1`) rather than
recorded a second time.
