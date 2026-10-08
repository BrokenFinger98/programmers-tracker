---
type: decision
project: programmers-tracker
tags: [backup, git, scheduling, logging]
author: BrokenFinger98
created: 2026-10-08
updated: 2026-10-08
sources: [decisions/2026-08-06-wire-git-into-the-pipeline, decisions/2026-10-08-reconcile-never-stages-the-state-directory]
---

# A backup that did not count backs off, and a repository with no remote says nothing

Date: 2026-10-08 · Status: accepted · Issue: #390

## Context

The backup check runs every minute (`tracker.backup.check-interval`, `PT1M`) and asks whether the most
recent scheduled hour has been backed up ([[decisions/2026-08-06-wire-git-into-the-pipeline]], decision
4). Since #372 a day counts only when reconciliation and the push both succeeded, so a day that did not
count stayed due, and every check tried it again.

Measured over one simulated day of checks a minute apart, with the real `DailyBackup`,
`CommandLineGitSync` and `FileBackupLog` on real repositories (git 2.48.1, on `e6deb23`, the head of #389 that main took as `cb78438`):

| Case | Reconciliations and pushes | WARN lines | Git work |
|---|---|---|---|
| No remote at all, one record to commit | 1,440 | 2,880: 1,440 "Daily backup could not push", 1,440 "git push skipped … no remote named origin has a URL" | 161 s |
| Held: 200 files, one note the content gate refuses, a local bare remote | 1,440 | 1,441: 1,440 refusals, 1 held line | 204 s |

A repository with no remote is a documented way to run (`docs/bootstrap.md`: "No remote. Expected if
you skipped that part of step 2"), and the boot report and `BackupSchedule` already say so once, at
INFO. #185 removed a warning repeated 1,440 times a day for the same reason. The review of #389 also
measured a failing pre-commit hook in the records repository running at every check, where before #372
it ran once a day.

`GitSync.push()` answers false both for a push that failed and for one with nowhere to go, so the
backup could not tell the setup from the fault.

## Options considered

**Telling "nowhere to push" from "failed"**

- **An outcome type for `push()`** (pushed, nothing to push, nowhere to go, failed). Exact, but every
  caller and four test doubles change for a distinction one caller needs.
- **A new port question, "would a push go anywhere".** A second question about remotes beside
  `hasRemote()`, free to disagree with it.
- **The existing `hasRemote()`, asked after `push()` answered false** — chosen. It is the question
  `BackupReporter` already asks to tell a repository nobody gave a remote (supported) from one that has
  a remote and is not pushing (a fault), so the report and the backup cannot disagree. Asked after the
  push rather than before, so a branch with no commit yet still answers true and counts (#372).

**What to do about a try that repeats**

- **Say each reason once per reason and head.** Quiet logs, but git, the hooks and the content search
  would still run 1,440 times a day. The content gate's warning sites are also being moved by #373.
- **Back off within one scheduled backup** — chosen. Fewer runs of everything, and the warnings fall
  with them. #372's held line keeps its own once per scheduled backup.
- **Try again as soon as the working tree changes.** A fix would be picked up within a minute and
  hooks would not run while nothing changes. Not taken: it needs a new port question and a git status
  at every check, and a fixed hook or a remote that comes back changes nothing git can see.

**The backoff's shape.** Doubling from a minute, the check's own pace, to an hour: seven tries in the
first two hours and hourly after, so a fix is picked up within the hour. A four-hour cap would cut a
standing refusal to about ten tries a day and leave a fix waiting up to four hours.

## Decision

1. **No port change.** `DailyBackup` asks `hasRemote()` after `push()` answered false: with no remote
   at all the day does not count and nothing is said; with one, "could not push" is said as before.
   `pushed()` asks the same question before its `NO_REMOTE` warning: with no remote at all it answers
   false and says nothing. A remote that exists while the branch pushes to another name, which has no
   URL, is still a fault and still said.
2. **A try that does not count is tried again on a backoff** (`BackupRetry`, pure): a minute after the
   first failure, then twice as long each time, at most an hour apart. The next scheduled backup is
   tried as soon as it falls due, and a restart tries at once. The day still counts only once its
   records are committed and pushed.
3. **The held line stays once per scheduled backup per process; "could not push" is said at each
   try.** Both say that the day is tried again, a minute later at first and at most an hour apart.

## Rationale

The same simulated day after the change (`82f2f97`):

| Case | Reconciliations and pushes | WARN lines | Git work |
|---|---|---|---|
| No remote at all | 29 | 0 | 2.7 s |
| Held | 29 | 30: 29 refusals, 1 held line | 3.9 s |

Neither day was recorded as backed up, before or after.

The backoff is a pure class so its arithmetic is tested without git or a clock: one minute after the
first failure, doubling, and an hour from the seventh failure on, every time up to the 200th. The shift
itself stops after six doublings. Without that stop, the wait overflowed `Duration` within those 200
failures and threw, in the mutation run; from the 59th failure on, a minute doubled that often no longer
fits.

## Accepted costs

- **A standing refusal still says its reason at each try**: about 29 lines a day rather than once. The
  content gate's warning sites move with #373; saying each reason once per head is left until then.
- **A fix waits for the next try**, up to an hour. A restart tries at once.
- **The backoff lives in memory.** A restart starts over, with a try at boot.
- **With no remote, reconciliation still runs on the backoff**, quietly: about 29 times a day, so
  records keep being committed locally.
- **`hasRemote()` answers about any remote, the push about the branch's own.** A repository whose only
  remote is not the one its branch pushes to is a fault: said at each try, on the backoff, and stale in
  `BackupReporter`, as it should be.
- **A failed push now costs one more git call**, `git remote`, to tell the setup from the fault.

## Outcome

Implemented on `fix/390-backup-says-it-once`: `6b8e03e` a remote-less push says nothing, `d55c40f`
no remote is not a failed push, `82f2f97` the backoff.

Tests, each red first:

- ten checks on a remote-less repository say nothing and record no backup (20 lines before);
- a remote-less push says nothing (the warning before);
- a held day across 120 checks is tried 7 times (120 before);
- a failing push across 16 checks says so 5 times (16 before) and lands at minute 31 once its remote
  exists;
- a fix is picked up at minute 123, not before (minute 70 before);
- the next evening's 23:00 is tried at once although the last backup waited until 23:08 (56 tries
  before, 7 after);
- `BackupRetryTest` (red as a compile error).

Two pins were green from the start: a misconfigured push remote is still said, and a remote whose URL
leads nowhere still says "could not push".

Mutants, each failing a test: the no-remote warning back in `pushed()` (2 tests); the misconfigured
remote never said (1); the backup warning on every false push (1) or on none (1); no backoff (4); the
backoff blind to the scheduled backup (2); failures not counted (6); no hour cap (3); no cap on the
shift (1); the held line at every try (3). One equivalent mutant: a try that counted keeping its retry
changes nothing, since the next check finds the day recorded and the next day is another scheduled
backup.

**On the real server.** Jars built from `e6deb23` and `82f2f97`, each booted against its own copy of a
scratch remote-less repository, with the backup due at once and checked every second
(`TRACKER_BACKUP_CHECK_INTERVAL=PT1S`). Every outside address pointed at a closed local port, the watch
token was a throwaway, and no GitHub token was set.

- **Before:** 134 "git push skipped … no remote" and 134 "could not push" in about 150 s, one pair a check.
- **After:** none in 200 s. Records dropped in at +34 s and +74 s were committed by the tries at 17:57:35
  (boot), 17:58:36 and 18:00:37 — a minute, then two.

Neither run recorded a backup. Both also logged, at boot, five lines about the scratch repository's own
synthetic submission line, which is no record; unrelated.
