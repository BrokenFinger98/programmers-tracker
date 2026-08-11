---
type: decision
project: programmers-tracker
tags: [git, durability, records, sensor, failure-taxonomy]
author: BrokenFinger98
created: 2026-08-11
updated: 2026-08-11
sources: [decisions/2026-08-06-wire-git-into-the-pipeline, decisions/2026-08-05-failure-taxonomy]
---

# A record living on one disk says so

## Context

`DailyBackup` pushes the record repository once a day and, when the push fails, leaves the day
due so the next start retries. That part is right. What it also does is log one line —

```
Daily backup could not push; the day stays due and the next start retries
```

— and then say nothing further, ever. `BackupLog.lastSuccessAt()` has always known when the
records last left the machine, and **nothing read it except the "is a backup due" check**.

So an expired deploy key, a changed remote, or a repository nobody gave one produces: records
committed locally, pushes failing, a warning that scrolled past days ago, and every record this
tool has ever written living on exactly one disk. The project's stated motivation is that 406
failures were already lost because Programmers keeps no history — a laptop that dies loses the
replacement too.

Found while verifying it: the author's record repository was **`ahead 2`** at that moment. Two
commits, never pushed, and the only way to know was to run `git status` by hand.

## Options considered

1. **Fail the boot when the records are stale.** Rejected outright: the capture path works
   perfectly well without a remote, and refusing to start would lose gradings to protect a backup.
2. **Put it on the badge.** Rejected. The badge answers "will my next submit be recorded", which
   is a question about the next thirty seconds; this is a slow-burning risk measured in days, and
   the badge vocabulary was already declared full in
   [[decisions/2026-08-11-a-watch-answer-is-not-a-promise]].
3. **Warn on every boot whenever nothing has been pushed.** Rejected as written — see below.
4. **Warn on every boot, but only when there is somewhere to push to.** Chosen.

## Decision

`BackupAge.of(lastSuccessAt, hasRemote, now)` is a pure calculator returning `Current`,
`NoRemote`, or `Stale(days, everPushed)`. Startup reports it at **every** boot rather than on the
day a push failed.

**No remote is not a fault.** The README is explicit that pushing needs credentials the tool
cannot invent, and that without them it still captures and still commits locally — that is a
supported way to run it, not a broken one. It is stated once, at INFO, as a fact about how this
copy is set up.

A remote that exists and is not being pushed to *is* a fault, and **never pushed** reads
differently from **gone stale**, because they ask the user for different things: check the
credentials you set up, versus find out what broke.

The tolerance is **two days**, chosen so an ordinary weekend of not opening the machine does not
raise it.

`backupAge()` is separated from the logging so tests assert a verdict rather than scrape a
logger. A check whose only output is a log line is a check nobody asserts on — which is how the
original warning came to fire once and be forgotten.

## Rationale

The whole product is a durable record of failures. A tool that keeps that record on one disk
while reporting nothing is not a smaller version of the product, it is a different one — and the
user cannot tell the difference from anything the tool shows them.

Distinguishing "no remote" is what makes the warning worth having. A boot-time alarm that fires
for a deliberate configuration is the kind people learn to scroll past, and then the real one
scrolls past with it.

## Accepted costs

- ⚠️ (old) — "**It only fires at boot.** A machine left running for a week never sees it change.
  The daily backup tick is the obvious place for a periodic version and it is not built."
  **Closed the same day by #185**: the tick now announces a change of kind, and the sequence that
  ended in silence — key expires Tuesday, every night's push fails, machine never restarted —
  now produces one warning when it starts and one all-clear when it stops. See the Outcome.
- **Two days is a guess.** Nothing measured says a day of unpushed records is acceptable and
  three is not; it is a compromise between a weekend and a real gap, stated rather than derived.
- **It counts time, not commits.** "Ahead 2 for ten minutes" is normal and invisible here, which
  is intended — but it means the warning says how *long* the exposure has lasted and never how
  *much* is exposed.
- **`hasRemote()` is one more thing `GitSync` does.** The port grows for a question that is not
  about syncing, which is the right home only because that is where `git` already lives.

## Outcome

Nine tests: five on the calculator including both never-pushed cases, four on the wiring —
asserting a verdict through a real scratch repository with and without a remote, rather than
through a logger.

Verified live only on the silent path: a real boot with a push from yesterday reported nothing,
which is correct and does not prove the warning. The failing path is covered by tests over a real
git repository, not by a live broken remote.


---

## Extended 2026-08-11 (#185): announced on change, not on state

The boot report leaves the machine that is never restarted, so `BackupReporter` now also answers
the minute tick — and the design question is entirely about **not** becoming noise. The tick fires
1,440 times a day; a warning repeated that often is one nobody reads, which is the same failure
the `NoRemote` case was introduced to avoid.

Three rules, each with a test:

- **Compared by kind, not by value.** A `Stale` that grew from nine days to ten is the same news.
  Comparing the number would fire once a day forever. The current number is stated at boot, where
  it is new information.
- **Recovering is announced.** A warning with no matching all-clear leaves a reader unable to tell
  a fixed problem from an unreported one.
- **Boot states unconditionally, the tick only on change**, and they share one reporter — two
  instances would each announce the same transition.

`never pushed` and `gone stale` stay separate kinds, so a deploy key that never worked and one
that stopped working are two announcements rather than one. They ask the user for different
things.
