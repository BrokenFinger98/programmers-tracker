---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [concurrency, storage, docker, filesystem, single-writer, measurement]
created: 2026-08-06
updated: 2026-08-11
sources: [decisions/2026-08-05-write-serialization, raw/sessions/2026-08-06-catalog-runners-and-the-record-repository.md]
---

# The record-repository lock: a kernel lock in `.git/`, and the mount where it does nothing

Date: 2026-08-06 · Status: accepted · Issue: #44

Elaborates [[decisions/2026-08-05-write-serialization]] decision 5 inside its own scope. It
reverses nothing: decision 5 already said the record repository is locked exclusively at
startup. This says with what, where the file lives, and — measured — where the mechanism
turns out not to work.

## Context

Decision 5 was recorded on 2026-08-05 and never implemented. There was no `FileLock` or
equivalent anywhere in `src/main/kotlin`, so the wiki asserted a safety property the code
did not have — worse than a missing feature, because a reader of the wiki was misled.

Shipping the container in #43 made the predicted scenario trivially reachable: `docker
compose up` while a local `bootRun` is alive, both bind-mounted onto the same records
directory. #43 said so in its own notes and filed this issue rather than papering over it.

### The apparent contradiction inside the ADR being elaborated

`2026-08-05-write-serialization` rejects `FileChannel.lock` in *Options considered* — "in-process
it is redundant, cross-process it is too coarse for our layout" — and then requires exactly
that primitive in decision 5. Both are right, because they are about different scopes:

| | Options considered | Decision 5 |
|---|---|---|
| Question | how to serialize **each derived write** inside one process | how to stop **a second process** existing |
| Granularity | one lock per write section | one lock per repository, per process lifetime |
| Verdict | too coarse — the writer dispatcher does it better and cheaper | exactly right — coarse is the point |

"Too coarse for our layout" was never a claim that file locks are wrong; it was a claim that
they are the wrong tool for ordering writes we already own. The next reader should not have
to re-derive that, hence this table.

## Options considered

- **A pid file** — rejected on the scenario itself. A pid from another namespace means
  nothing to a container, which is the exact double-writer being prevented, and a pid file
  outlives `kill -9`, so every stale one has to be guessed about.
- **`FileChannel.tryLock`** — chosen. Advisory but kernel-enforced across processes, and
  released by the operating system when the holder dies however it dies.
- **A lock file at the record-repository root** — rejected as the primary placement.
  `CommandLineGitSync.reconcile` runs `git add --all`, so the lock would be committed and
  pushed to a public records repository. Kept only as the fallback below.
- **`.gitignore` entry instead of placement** — weaker. An ignore rule is a claim about a
  file; a commit is a fact, and existing user repositories have no such rule.
- **Waiting for the lock, or degrading to read-only** — rejected. The interesting failure is
  a user who does not know they started two, and a process that waits looks like a hang.

## Decision

1. **`FileChannel.tryLock` on a file in `.git/`, held for the process lifetime.** Placement
   is the load-bearing part: `.git/` is never tracked, so `git add --all` cannot reach it.
2. **Both refusals are handled.** `tryLock` returns `null` when another *process* holds the
   lock and throws `OverlappingFileLockException` when the *same JVM* already does. Handling
   only the first makes same-JVM tests pass while proving nothing.
3. **No `.git/`, no repository — the lock falls back to the record-repository root.** A
   directory nobody ran `git init` on has nothing that could commit the file, and the lock is
   still cross-process because what is shared is the mount, not the repository. The one
   window this leaves — a run before `git init`, then `git init`, then a run after it — is
   closed from both sides: the root file is deleted as soon as a `.git` exists, and the name
   is in `template/ps-records/.gitignore`.
4. **A linked worktree's `.git` file is followed** to the directory its `gitdir:` line names.
   An unreadable pointer falls back to the root and is safe by consequence: git cannot run in
   a repository whose `.git` file it cannot follow, so nothing commits at all.
5. **Refusal happens during bean instantiation**, which precedes both the web server
   accepting a connection and every `ApplicationRunner` — so it lands before `/watch` answers
   anybody and before `reconcileAtStartup` runs `git add --all`. Verified by booting the real
   application and asserting that neither `WebServerInitializedEvent` nor
   `ApplicationStartedEvent` was ever published.
6. **The refusal is a `FailureAnalyzer`**, not a stack trace: APPLICATION FAILED TO START,
   the repository path, and what to do. Paths only — no credential is ever in it.
7. **`TRACKER_RECORD_REPO_LOCK=false` exists** for a filesystem that cannot lock at all, and
   warns on every start. Without it, such a filesystem would leave the tool unstartable.

## What was measured, and the finding that matters

Run on 2026-08-06, macOS 15 (arm64), JDK 25, Docker Desktop 29.3.1. Four probes, each a real
second process running the real boot jar:

| Case | Second instance | Verdict |
|---|---|---|
| Two native JVMs, same records directory | **refused**, exit 1, no Tomcat, formatted failure block | works |
| Two processes in one container, records on the container filesystem (overlayfs) | **refused** | works |
| Two processes in one container, records on a **host bind mount** | **started** | **no protection** |
| Host JVM + container, same **host bind mount** | **started** | **no protection** |

**Docker Desktop for macOS does not honour POSIX record locks on a bind mount.** `tryLock`
returns a lock that excludes nobody, and it does so *silently* — no `IOException`, so neither
the refusal nor the escape hatch ever fires. The overlayfs case proves the JVM and the Linux
side are fine; the boundary is the virtualised filesystem between host and VM.

That is exactly the layout `compose.yaml` ships, so on macOS the headline scenario — container
plus a local run — remains unprotected. It is stated in `docs/bootstrap.md` next to the
warning that was already there, rather than quietly implied to be fixed.

### The Linux row, measured by accident an hour later

The row above marked *expected but unverified* — a native Linux host, where the bind mount is
a real filesystem on the shared kernel — got measured immediately, and not on purpose. CI's
docker job starts two containers to compare bind addresses, and both mounted the same records
directory. With the lock in place **the second refused to start**, failing the job:

```
The record repository /records is already held by another programmers-tracker
instance, which is holding /records/.git/.programmers-tracker.lock.
```

| Case | Second instance | Verdict |
|---|---|---|
| Two containers, same **host bind mount**, ubuntu-latest runner | **refused** | works |

So the mechanism is sound and the boundary really is Docker Desktop's virtualised filesystem,
not containers or bind mounts as such. The CI failure was the feature working, and the fix was
to the test — one records directory per container, since those two steps are about network
posture. A dedicated step now asserts the refusal deliberately, so the measurement is
permanent rather than accidental.

Worth keeping as a lesson about what "unverified" is worth: this one was labelled honestly
rather than assumed, and an hour later the evidence arrived from a direction nobody planned.
Had the assumption been written as fact, the CI failure would have read as a bug in the lock.

**Still unverified**: a native Linux host running a JVM *outside* any container against the
same mount (only container-to-container was observed), Windows, and network filesystems.

## What the lock does not cover

- **`.ps/`** — raw frames, per-problem timers, the daily-backup marker and the generated
  `/watch` token. It is shared between a container and a native run exactly as the records
  are, and it is not the record repository. Nothing locks it.
- **Docker Desktop bind mounts**, per the measurement above.
- **Anything a user does by hand** in the repository while the tool runs. The lock is
  advisory; it stops a second tracker, not a second person.

## Accepted costs

- A second instance refuses to start rather than degrading — the cost decision 5 already
  accepted, now real, and surprising the first time it happens in development.
- The root fallback is a second placement to reason about, kept only because refusing to
  start a records directory that has no repository would be worse.
- The escape hatch can be left on by mistake. It warns on every start, which is the most a
  switch like this can do.
- Two child JVMs in the test suite (~2 s). A same-JVM test would prove the wrong property.

## Outcome

Implemented in `fix/44-record-repo-lock` (#44):
`adapter/store/RecordRepositoryLock`, `adapter/store/RecordRepositoryLockedFailureAnalyzer`,
`adapter/config/RecordRepositoryConfiguration`, 17 tests across three classes.

The gap the measurement found is not closed by this issue and is not pretended to be. If it
is to be closed, it needs a mechanism that survives a filesystem with no locks — a heartbeat
marker aged out by mtime is the obvious candidate, and it is a different decision from this
one. Tracked as **#52**, which carries the measurement table and what a weaker mechanism
would have to own. Related: [[decisions/2026-08-06-container-network-posture]] ·
[[decisions/2026-08-05-git-retry-scope]] · [[concepts/assumption-vs-measurement]].
