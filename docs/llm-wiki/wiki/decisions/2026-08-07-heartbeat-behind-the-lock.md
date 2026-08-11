---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [concurrency, filesystem, docker, liveness, single-writer]
created: 2026-08-07
updated: 2026-08-11
sources: [decisions/2026-08-06-record-repository-lock, raw/sessions/2026-08-07-adversarial-review.md]
---

# A liveness marker behind the lock, for filesystems that report a lock they do not enforce

Date: 2026-08-07 · Status: accepted · Issue: #52

Elaborates [[decisions/2026-08-06-record-repository-lock]]. It reverses nothing: the kernel
lock stays primary and settles the question wherever locks work. This covers the filesystem
where it silently does not.

## Context

`RecordRepositoryLock` was built for one scenario — `docker compose up` while a local
`bootRun` is alive, both bind-mounted onto the same records directory. Measuring it found
that **on a Docker Desktop bind mount it protects nothing**:

| case | second instance |
|---|---|
| two native JVMs | refused |
| two processes in one container, overlayfs | refused |
| two containers, host bind mount, Linux runner | refused |
| **container + host, Docker Desktop bind mount** | **started** |

`tryLock` returns a lock that excludes nobody and raises nothing, so neither the refusal nor
the `TRACKER_RECORD_REPO_LOCK` escape hatch fires. The scenario the decision existed for was
the one it did not cover, on the two platforms most users are on.

## Options considered

- **Fix the lock** — there is nothing to fix. `FileChannel.tryLock` is the correct primitive;
  the filesystem does not implement locking. Any answer has to survive that.
- **A pid file** — rejected for the reasons it was rejected the first time. A pid from another
  namespace means nothing across a mount, and a pid file outlives `kill -9`, so every stale
  one has to be guessed about.
- **A marker aged by mtime** — the obvious shape, and wrong in the one case that matters. Age
  means comparing a file's timestamp against *our* clock, and a container and its host can
  disagree about what time it is. Skew either refuses a free repository or admits a second
  writer, and it does so silently.
- **A marker compared for change (chosen)** — a holder rewrites it every beat; a starter reads
  it, waits, reads again. Changed means alive.

## Decision

1. **The kernel lock stays primary.** The heartbeat runs behind it and only reaches its own
   check where the lock did not settle the question.
2. **Change, not age.** Equality of two reads, never a timestamp comparison — so nothing
   depends on two machines agreeing about the time.
3. **The marker token is unique per write** — pid, `nanoTime`, and a counter. Each part earns
   its place: the pid separates processes, the counter separates beats, and `nanoTime`
   separates two instances inside one process. A test caught exactly that hole, where a
   takeover produced a byte-identical marker and made a live holder look stale.
4. **The marker lives beside the lock, inside `.git/`.** `CommandLineGitSync.reconcile` is
   `git add --all`, and a file rewritten every four seconds in the working tree would be
   committed and pushed to the user's records repository over and over.
5. **Beat 4 s, watch 12 s.** The watch must stay comfortably longer than the beat or a live
   holder can look stale; both are configurable for a slower filesystem.
6. **The refusal says which mechanism refused**, because the two recover differently.
7. **It never fails a start on its own.** A marker that cannot be written logs and continues —
   this is a net over the lock, not a gate in front of it.

## Rationale

Comparing for change is what makes this work across a boundary where nothing else is
reliable. The mount gives us `write` and `stat` and no guarantees about locking or clocks;
"did this file change while I watched" needs only the first two.

Naming the mechanism in the refusal is not cosmetic. A kernel lock is gone the instant its
holder dies, while a heartbeat has to be *observed* to stop changing — telling a user the
wrong one sends them to wait for something that will not happen.

## Accepted costs

- **Strictly weaker than a lock.** Two instances started inside the same watch window can both
  see no change and both proceed. The kernel lock closes that on every filesystem that
  implements locking; this closes the common case on the one that does not.
- **A start after a crash waits one watch window** — 12 s — before taking over. Better than a
  pid file's alternative, which is a file the user has to delete, but not free.
- **Two mechanisms to reason about**, and a reader must now ask which one refused. Mitigated by
  saying so in the message.
- **A marker is written every four seconds, forever.** Cheap, but it is I/O on the user's
  records directory that would not otherwise happen.

## Outcome

Implemented 2026-08-07 in `adapter/store/RepositoryHeartbeat`, wired behind the lock bean.
773 tests.

**Verified where it matters**: two containers on the same Docker Desktop bind mount — the
exact configuration `compose.yaml` produces and the one the kernel lock could not cover. The
second refused, with the heartbeat-specific recovery text:

> This one was refused by the liveness marker rather than by a file lock, which means the
> filesystem holding your records does not enforce locks — a Docker Desktop bind mount is the
> usual reason. After the other instance stops, the next start waits one watch window and
> takes over on its own; nothing needs deleting.

Still unverified: Windows, and network filesystems. The mechanism needs only `write` and
`stat`, so it is *expected* to hold there, and that expectation is not a measurement.

Related: [[decisions/2026-08-06-record-repository-lock]] ·
[[decisions/2026-08-05-write-serialization]] · [[concepts/assumption-vs-measurement]].
