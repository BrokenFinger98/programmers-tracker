---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [git, backup, scheduling, storage, single-writer, startup]
created: 2026-08-06
updated: 2026-10-08
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md, raw/sessions/2026-08-06-catalog-runners-and-the-record-repository.md]
---

# The commit rides with the writer, and the daily backup asks rather than fires

Date: 2026-08-06 · Status: accepted · Issue: #41

## Context

[[decisions/2026-08-05-git-retry-scope]] closed with the sentence this issue exists to
delete: *"Nothing is wired yet: no caller commits, and the 23:00 backup run is not
scheduled."* `GitSync` was the third capability in a row to land with no caller, and unwired
code is indistinguishable from working code until someone looks.

Wiring it raised three questions the earlier ADRs had not had to answer.

**Where the commit runs.** [[decisions/2026-08-05-capture-pipeline-stages]] files the commit
under stage 3 (cold), and `RecordWriter` is stage 2 (warm) — its own KDoc said it does not
touch git. But [[decisions/2026-08-05-write-serialization]] decision 1 says derived writes are
serialized by confining them to one dispatcher, and git has exactly one index.

**How a daily 23:00 run survives a laptop.** The design (§4.6) requires the backup and adds
that a missed run "is caught up at the next start rather than skipped". A cron entry fires or
does not; it has no opinion about the firing it slept through.

**What a fresh install looks like.** `~/ps-records` on a new machine is a directory. Nothing
in the earlier work said what happens when it is never made a repository.

## Options considered

**Where the commit runs**

- **A stage-3 component beside the writer, on the same dispatcher.** Keeps the stage table
  literal. Costs a second call site — `ChannelCapture` and `RawSessionReconciler` both write
  records — and a capability wired at two call sites is a capability half-wired at the next
  one. That is the failure this issue is fixing.
- **Fire-and-forget from a scope after the write returns.** Removes even the bounded delay,
  but needs a scope, a shutdown drain and an ordering story — the machinery
  [[decisions/2026-08-05-write-serialization]] rejected when it chose confinement over a
  consumer coroutine.
- **Inside the writer's confined section, right after the append** — chosen.

**The daily schedule**

- **`@Scheduled(cron = "0 0 23 * * *", zone = "Asia/Seoul")`.** Punctual, and states the hour
  a second time next to the one the catch-up logic needs — two spellings of one fact, free to
  drift. Fires nothing at all on a night the machine slept.
- **Cron plus a separate startup catch-up.** Both of the above, plus a second code path that
  only runs at boot.
- **A tick that asks whether the most recent scheduled hour has been backed up** — chosen.
  One question, one place, and its answer is the same whether the process was awake at 23:00,
  asleep, or not yet started.

**A records directory that is not a repository**

- **Fail every call and log each one.** Correct and unusable: every submit would log the same
  line forever and bury everything else the tool says.
- **Detect once, say so once, skip afterwards** — chosen.

## Decision

1. **`RecordWriter` commits, inside `withContext(writerDispatcher)`, immediately after the
   append.** The record and its history are one derived write. Stage 3's *semantics* are
   untouched — a git failure is logged where it happened and left for the next reconciliation
   — but its *placement* is the writer's section, because a second writer next to the confined
   one is exactly what that decision exists to prevent.
2. **The commit is scoped to the paths that exist.** The submission log always; the attempt's
   raw file when the move into `problems/` succeeded. A pathspec git cannot match fails the
   whole partial commit, and the move is best-effort by design.
3. **The writer guards the call with `runCatching` even though the port promises not to
   throw.** Not defensive habit: the record is already durable at that point, so an escaping
   exception would take the capture key down with it (`write()` removes the key when the body
   throws) and let a reconnect replay record the grading twice.
4. **`DailyBackup` compares an injected clock against a persisted instant.** Due when the most
   recent 23:00 Asia/Seoul is later than the last successful backup — `null` counts as due.
   `BackupSchedule` ticks once a minute and asks; `StartupReconciliation` asks once at boot.
   Only a push that landed is recorded, so a failed one leaves the day due.
5. **`StartupReconciliation` sequences the three recoveries in one place**: raw sessions become
   records, `git.reconcile()` commits whatever is uncommitted, then the backup catches up.
   The order is load-bearing — reconcile first and it misses the records the sessions were
   about to write.
6. **`CommandLineGitSync` asks `git rev-parse --git-dir` once, lazily, per instance.** Not a
   repository means one warning naming the directory and `false` from every call for the life
   of the process. A repository created afterwards is deliberately not noticed.

## Rationale

- Decision 1 is measured, not argued: `RecordWriterSerializationTest` now routes the append
  **and** the commit through one in-flight counter and drives 64 concurrent settlements
  through it — peak occupancy stays 1, so no commit ever overlaps another grading's append.
- Decision 4's shape is what makes the catch-up testable at all. Every case — the evening
  after the hour, the morning after a night that was slept through, the morning after a night
  that was not, a restart that must not repeat the backup — is a fixed `Clock` and an assertion,
  with no waiting anywhere.
- Decision 6 uses `rev-parse` rather than testing for a `.git` directory because that also
  covers a worktree (whose `.git` is a file) and a records directory nested inside some other
  repository, which would otherwise commit a learner's records into a repository nobody meant.
- Every test drives real temporary repositories and a local bare remote (git 2.48.1,
  2026-08-06). A `GitSync` double would agree with whatever our code believes.

## Accepted costs

- **A commit runs on the global writer, so it delays the next grading's record by its own
  duration.** Bounded by the 60 s process timeout and, on a pass, by a push over the network.
  The event rate is one human solving problems, and the record is already durable before git
  starts — but this is a real serialization cost and it is the price of one writer.
- **The tick asks every minute, so the backup happens within a minute of 23:00 rather than on
  it.** Nobody watches the clock for a backup, and the alternative was two spellings of the
  hour.
- **A repository with no remote logs a failed push once per start and once per day.** Honest
  (nothing left the machine) but it is a log line for a configuration many users will have
  deliberately.
- **A directory that becomes a repository while the process runs stays uncommitted until
  restart.** The message says so; the records are on disk either way.
- **The context test now redirects every path into a scratch directory.** Booting Spring runs
  the startup reconciliation, and `git add --all` against a developer's real `~/ps-records`
  would commit whatever they had pending. Worth restating: a test that boots the application
  is a test that runs its startup effects.

## Outcome

Implemented 2026-08-06 in `RecordWriter`, `StartupReconciliation`, `DailyBackup` +
`FileBackupLog`, `GitConfiguration` and the detection in `CommandLineGitSync` (#41), with 28
new tests over real temporary repositories (537 in the suite). Still unwired from the commit:
the derived artifacts of #34 (solution file, diff, README) — they have no producer yet, so a
submit commit today carries the log and the raw frames only. The MCP `push()` trigger
(design §4.6) also has no caller, because MCP itself does not exist yet.

**Amended 2026-10-08 (#372): a day counts only once its records are committed.** Decision 4
recorded a day whenever the push landed, and the answer of `git.reconcile()` was never read.
#360's adversarial review found the consequence: a reconciliation the content gate refused, one
waiting out the user's merge, or one an index lock never let through left records on this disk
alone, while the backup log said the day was covered and the next check found nothing due. Now:

- The day is recorded only when `reconcile()` and `push()` both answer true. "Nothing to
  reconcile" already answered true and every failure false, so the `GitSync` port kept its
  signature; its KDoc now says what each answer means.
- The push still runs when reconciliation failed: what is committed leaves the machine, and only
  the record of the day waits.
- A branch with no commit yet and nothing to commit counts. Nothing exists to back up, and #360
  made such a push answer "nothing to push". One whose reconciliation was refused does not count:
  there, #360's answer alone would have recorded a day whose records exist only here.
- A held day is said once for each scheduled backup. The reason is said by the reconciliation
  itself.

Weighed and not taken: skipping the push while reconciliation fails, which keeps every committed
attempt at home behind a note the gate refuses or a merge left open; and an outcome type in place
of the port's boolean, which would tell "nothing to do" from "done" — a difference no caller acts
on.

Accepted costs. While a day is held, every check reconciles and pushes again, once a minute: the
push usually sends nothing, but each is a round trip to the remote. A persistent refusal is
warned about at every check by the reconciliation, as a failing push already was. A directory git
cannot open does not hold the day: reconciliation commits the rest, answers true and names the
directory once ([[decisions/2026-10-08-reconcile-never-stages-the-state-directory]], its F8).

Six new `DailyBackupTest` cases were red first on the old code (`expected:<false> but was:<true>`):
the token search's refusal, a merge in progress, an index lock that never clears, a branch with no
commit yet whose reconciliation was refused, what is committed still going up, and the held day
said once. The no-commit-yet day that counts is a pin, green before. Five mutants each fail a test:
the reconciliation's answer ignored (6 tests), the push skipped while held (1), the held line at
every check (1) or once per process (1), and a push with no commit yet answering false (1).
