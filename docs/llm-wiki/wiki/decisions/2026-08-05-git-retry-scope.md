---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [git, retry, idempotency, storage, adapter]
created: 2026-08-05
updated: 2026-08-05
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md]
---

# Git retries lock contention only; every other failure waits for reconciliation

Date: 2026-08-05 · Status: accepted · Issue: #39

## Context

[[decisions/2026-08-05-write-serialization]] decision 4 settled the *posture*: git is a
separate, retryable reconciliation step, "commit whatever is uncommitted" is idempotent,
contention on `index.lock` is expected because the lock is not ours to own, and a git
failure never fails a capture. It did not settle **what retrying means in the adapter** —
whether every failure is retried, how the commit is scoped, or what a commit says.

Building it forced four questions the ADR left open.

## Options considered

**Retry policy**

- **Retry every failure on a bounded schedule.** Uniform, and wrong: a non-fast-forward
  push, a directory that is not a repository, and a pathspec matching nothing cannot heal by
  waiting. Retrying them spends a capture's time pretending they might.
- **Retry nothing, leave everything to the next reconciliation.** Honest but weak: the
  editor's lock clears in well under a second, and giving the commit up for that would leave
  the record uncommitted until whenever the next reconciliation happens to run.
- **Retry only contention** — chosen.

**Client**

- **git CLI** — chosen. The repository is the user's own and other processes are running git
  against it; the index lock, the partial commit and the push refspec are git's semantics,
  and shelling out gets exactly them.
- **JGit** — reimplements that surface, adds a dependency to a public repository, and still
  has to interoperate with the git the user's editor runs.

**Commit scoping**

- `git add <paths>` **then a plain commit** — excludes what is merely dirty, but carries
  whatever a *staged* unrelated file left in the index. An editor's git integration stages
  files exactly this way.
- `git add <paths>` **then `git commit -- <paths>`** (a partial commit) — chosen. Measured:
  a `notes.md` both dirty and staged stays out of the submit commit and stays staged
  afterwards.

## Decision

1. **Contention is the only retried failure.** `CommandLineGitSync` retries while git's
   output names `index.lock` or "Another git process", 5 attempts over 100/200/400/800 ms,
   then gives up. Every other non-zero exit returns immediately, logged with git's own
   words. Both paths end the same way — `false`, never an exception, and the work is left
   for the next `reconcile()`.
2. **The port returns booleans and never throws.** `GitSync.commitSubmission` /
   `reconcile()` / `push()` report whether the work is done. Waiting is injected as a
   function, so the schedule is testable without sleeping.
3. **Submit commits are path-scoped partial commits**; reconciliation is the deliberate
   catch-all (`git add --all`). A path outside the record repository is dropped rather than
   staged — publishing a file the user never meant to publish is not a failure to make
   quietly.
4. **The commit subject degrades rather than invents** (design §4.6). An unknown level drops
   the `[LvN]` bracket, an empty title falls back to the lesson id — the same fallback
   `ProblemReadme` already applies, for the same reason. Today *every* record has an empty
   title, so this is the normal case, not an edge one.
5. **A failed push is not a failed commit.** `commitSubmission` returns true once the commit
   lands; the push is retried by the next pass or the manual trigger.

## Rationale

- Measured against real repositories under `@TempDir`, not mocks: an `index.lock` created by
  hand makes `git add` exit 128 with "Unable to create … File exists" while `git status`
  still exits 0, which is what makes contention detectable from the output at all
  (git 2.48.1, 2026-08-05).
- The retry split follows from what can heal. Contention is another process finishing its
  own commit — a wait fixes it. A rejected push needs a human or a pull; retrying it four
  times only delays the log line that says so.
- Keeping `false` and a log where a throw would be is what makes decision 4 of the
  write-serialization ADR true in practice rather than in intent.

## Accepted costs

- **Contention is detected by matching git's message text.** A git release that reworded
  both spellings would turn a retryable failure into an immediate one — degraded, not
  broken, since reconciliation still picks the work up, but it is a string dependency and it
  is worth restating that it is one.
- **A lock held longer than ~1.5 s loses the scoped commit**; those files then land in a
  reconciliation commit with a generic subject instead of their own. The attempt history
  stays complete, the `git log` line for that attempt does not.
- **Pushing moves the whole branch.** A pass on one problem pushes every pending commit of
  every other. "Never pushed until solved" describes the trigger, never the scope
  (design §4.6) — this is recorded in the code as well, because it will otherwise be
  rediscovered as a bug.
- **`git push` inherits whatever the user configured.** No remote or branch is hardcoded, so
  a repository with no upstream simply reports a failed push rather than guessing `origin`.
- The 60 s process timeout is defensive and untested — there is no cheap way to hang a real
  git — and it exists because a push stalling on credentials would otherwise stall a caller.

## Outcome

Implemented 2026-08-05 in `adapter/git/CommandLineGitSync` + `adapter/git/CommitMessage`
behind `application/GitSync` (#39), with 26 tests over real temporary repositories and a
local bare remote. **Nothing is wired yet**: no caller commits, and the 23:00 backup run
(design §4.6) is not scheduled — the entry point exists, the schedule does not.
**Wired 2026-08-06 in #41** — see [[decisions/2026-08-06-wire-git-into-the-pipeline]].
