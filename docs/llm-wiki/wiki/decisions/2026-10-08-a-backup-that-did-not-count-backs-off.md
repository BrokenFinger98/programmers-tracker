---
type: decision
project: programmers-tracker
tags: [backup, git, scheduling, logging]
author: BrokenFinger98
created: 2026-10-08
updated: 2026-10-10
sources: [decisions/2026-08-06-wire-git-into-the-pipeline, decisions/2026-10-08-reconcile-never-stages-the-state-directory, raw/sessions/2026-10-08-seventeen-prs-through-one-queue.md]
---

# A backup that did not count backs off, and a repository nobody gave a remote says nothing

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
inferred, from the code, that a failing pre-commit hook in the records repository would run at every
check, where before #372 it ran once a day; the review of #399 was the first to measure it.

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
standing refusal to 13 tries a day, by the same count as the 29 below, and leave a fix waiting up to
four hours.

**Telling a repository nobody gave a remote from one whose remote was wanted** (the review of #399).
Silence for the first also silenced the second: a remote removed after the records reached it, and a
`GITHUB_TOKEN` whose wiring at start failed. The boot report says `NoRemote` for both, at INFO, which
also hides how stale the records are.

- **Say "no remote" at every try again.** It is what #390 removed: 1,440 lines a day for the supported
  setup.
- **Make the boot report and `BackupSchedule` say `Stale` when a backup was recorded before.** Not
  taken here: it changes what the report's kinds mean for one case, and a backup whose only success was
  "nothing to push" would be told its records stopped reaching a remote they never reached.
- **The backup asks for evidence that a remote was wanted** — chosen: a backup recorded before, or a
  push credential stored, which only a token given to the tracker leaves. The credential is known by a
  new port question, `GitSync.hasPushCredential()`, beside `hasRemote()`: `CommandLineGitSync` already
  holds `PushCredential`, and the application must not. It is answered by the store's presence, never
  its content.

## Decision

1. **`push()` keeps its Boolean.** `DailyBackup` asks `hasRemote()` after `push()` answered false: with
   no remote at all the day does not count and nothing is said; with one, "could not push" is said as
   before. `pushed()` asks the same question before its `NO_REMOTE` warning: with no remote at all it
   answers false and says nothing. A remote that exists while the branch pushes to another name, which
   has no URL, is still a fault and still said. `hasRemote()` never throws: when git cannot even start,
   as with the records directory gone, it answers false and warns (the review of #399).
2. **A try that leaves the day due is tried again on a backoff** (`BackupRetry`, pure): a minute after
   the first failure, then twice as long each time, at most an hour apart. Whether the day is still due
   decides, not what the try answered, so a try that threw, or answered true while its record was not
   written, backs off too (the review of #399). The next scheduled backup is tried as soon as it falls
   due, and a restart tries at once, as does a check whose clock was set back to before the failure. The
   day still counts only once its records are committed and pushed.
3. **The held line stays once per scheduled backup per process; "could not push" is said at each
   try.** Both say the day is tried again after a wait that doubles with each failed try, from a minute
   up to an hour. They said "a minute later at first", which, said at every try, was false from the
   second (the review of #399).
4. **A remote that was evidently wanted, and is missing, is said** (the review of #399). With no remote,
   the backup asks for evidence that one was wanted: a backup recorded before, or a push credential
   stored, which only a token given to the tracker leaves (`GitSync.hasPushCredential()`, the one port
   question added, answered by the store's presence). With either, "could not push: no remote, yet one
   was meant to be there" is said once for each scheduled backup per process, as the held line is; with
   neither, nothing. The boot's wiring failure no longer promises that the backup will say so; it says
   that a restart with the token tries again.

## Rationale

The same simulated day after the change (`82f2f97`):

| Case | Reconciliations and pushes | WARN lines | Git work |
|---|---|---|---|
| No remote at all | 29 | 0 | 2.7 s |
| Held | 29 | 30: 29 refusals, 1 held line | 3.9 s |

Neither day was recorded as backed up, before or after.

The backoff is a pure class so its arithmetic is tested without git or a clock: one minute after the
first failure, doubling, and an hour from the seventh failure on, every time up to the 200th. The shift
itself stops after the doublings that take a minute to an hour or past it, six, counted from the two
since the review of #399 rather than written down beside them. Without that stop, the wait overflowed
`Duration` within those 200 failures and threw, in the mutation run; from the 59th failure on, a minute
doubled that often no longer fits.

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
- **A failed push now costs one more git call**, `git remote`, to tell the setup from the fault; with
  no remote, also a read of the backup log and a look at the credential store, on the backoff.
- **The boot report still calls a missing remote a supported way to run** (the review of #399). It says
  `NoRemote` at INFO whether or not one was wanted, which also hides how stale the records are; the
  backup's warning, once for each scheduled backup, is what says otherwise. A `GITHUB_TOKEN` whose
  wiring failed hears the wiring's warning, the backup's, and that INFO line, in that order.
- **The evidence that a remote was wanted is circumstantial.** A remote removed on purpose is warned
  about once a day until the evidence goes: the token out of `.env`, `.ps/git-credentials` and
  `.ps/backup.json` deleted, as `docs/bootstrap.md` says. A remote-less repository whose first backup
  found nothing to push records that day as backed up, and is then taken to have wanted a remote. A
  credential never stored, because `.ps` was refused at boot, is no evidence; the boot said that one.
- **With git unable to start, `hasRemote()` warns at every ask**, and `BackupReporter` asks at every
  check: a records directory gone is said each minute, where before the check threw each minute.

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
shift (1); the held line at every try (3). This round also called one mutant equivalent, a try that
counted keeping its retry, "since the next check finds the day recorded". The review of #399 showed it
was not: a try can answer true while its record was not written, and then the next check finds the day
still due; a try that threw never reached that line at all. Both tried at every check.

**The review of #399** (approved, nothing blocking) asked for six changes, all made on this branch, with
this page and `docs/bootstrap.md` corrected beside them. Each code change was red first:

- `934627d` the factory is `BackupRetry.of` (dev rules §5), and its doublings are counted from `FIRST`
  and `LONGEST` (red: a compile error);
- `baffe2b` a clock set back tries at once: the retry keeps when the try failed and waits only from
  then (red: set back 10, 30 and 60 minutes, each waited);
- `34e80ef` a try that threw still backs off: whether the day is still due decides, in a `finally`
  (red: a log whose write throws gave 60 pushes and 60 exceptions in 60 checks, and a write skipped
  silently 60 pushes; 6 each after). A pin that a recorded try leaves no backoff behind kills the
  mutant above;
- `7fa8404` `hasRemote()` answers, never throws (red: a records directory gone threw `IOException` out
  of `hasRemote()` and of `runIfDue()`);
- `46fe100` the retry lines hold at every try (red: all five "could not push" lines said "a minute
  later");
- `f7dba56` the wiring's failure says a restart tries again (red: it named no restart);
- `26e4b4d` a remote that was wanted and is missing is said once for each scheduled backup (red: a
  removed remote and a stored credential were each said zero times).

Mutants of that round, each killed: the doublings one short (4 tests) or past the overflow (1); no
lower bound on the wait (3), an exclusive one (4), the scheduled backup not compared (2); no `finally`
(1), a `finally` deciding by the answer (1), a recorded try keeping its retry (1); `hasRemote()`
unguarded (2), guarded without the warning (1), the unknown answered as a remote (1); the missing remote
never said (2), said at every try (2), said as a failed push (2), a recorded backup no evidence (1), a
stored credential no evidence (1), no evidence asked (1); the credential judged by reading it (1), by
following a link (2), never reported by the adapter (2).

**On the real server.** Jars built from `e6deb23` and `82f2f97`, each booted against its own copy of a
scratch remote-less repository, with the backup due at once and checked every second
(`TRACKER_BACKUP_CHECK_INTERVAL=PT1S`). Every outside address pointed at a closed local port, the watch
token was a throwaway, and no GitHub token was set.

- **Before:** 134 "git push skipped … no remote" and 134 "could not push" in about 150 s, one pair a check.
- **After:** none in 200 s. Records dropped in at +34 s and +74 s were committed by the tries at 17:57:35
  (boot), 17:58:36 and 18:00:37 — a minute, then two.

Neither run recorded a backup. Both also logged, at boot, five lines about the scratch repository's own
synthetic submission line, which is no record; unrelated.
