---
type: decision
project: programmers-tracker
tags: [storage, links, security, state, windows]
author: BrokenFinger98
created: 2026-10-08
updated: 2026-10-08
sources: [decisions/2026-10-08-reconcile-never-stages-the-state-directory, decisions/2026-10-08-one-replace-and-no-crash-debris]
---

# State is written in the directory it checked, held open

## Context

#360 checked `.ps` before every write of state and left one window open, N10 in its review: every
check is of a path at one moment, and the write resolves that path again
([[decisions/2026-10-08-reconcile-never-stages-the-state-directory]]). A link swapped in for `.ps`, or
for a directory below it, after the check and before the write leads the write wherever it points, a
directory git tracks included. The window was in two places:

- **between the state directory's check and a write.** `forWriting()` and `forGit()` check `.ps` and then
  ask git whether it tracks anything there, which takes milliseconds; the writer then writes to
  `<root>/.ps/<name>`.
- **between a writer's stat of its directories and its open.** The raw log stat'ed `.ps`, `raw`, and
  `recorded` or `orphans` before every frame, then opened `.ps/raw/<session>` by path.

**Measured on main (`258ed10`) before anything changed.** `.ps` was deleted, as a checkout deletes an
ignored directory, and a link to `problems/zz` put in its place, while git answered (the test's
`ChangingAnswer` runs a swap during the question):

- the timers document was written into `problems/zz`; with `.ps` moved aside instead, the same;
- the push credential — `git-credentials`, the token in it — was written into `problems/zz`, with only a
  constructor seam added to `GithubRemote` to hand the test's answer in.

The content gate of #360 and #373 would still refuse to commit or push that file. That is defence in
depth, not a reason to leave the window: the token lay in the records tree, where any other tool that
stages the tree could take it.

#386 had just made the replace one class, `FileReplacement`, with every operation on the target's
directory in it, so that a directory handle would change that class rather than the writers
([[decisions/2026-10-08-one-replace-and-no-crash-debris]]). #374 is built on #386.

The constraints: the tracker runs as Linux in a container over a macOS bind mount; CI runs Linux, macOS
and Windows; reads stay as #387 left them; `RecordWrites` and `RecordBound` are not changed.

### What the platforms give

`java.nio.file.SecureDirectoryStream` is a directory held open, every operation on it relative to it:
`openat`, `renameat`, `unlinkat`, `fstatat`. Probed with JDK 25, by a scratch program and by the contract
tests themselves — 41 cases then, since split between `DirectoryHandleTest` and `SecureDirectoryHandleTest`
— run inside the image with JUnit's launcher:

| Where | Store type | `newDirectoryStream` gives | Contract cases |
|---|---|---|---|
| the host, macOS, JDK 25.0.3 | `apfs` | `UnixSecureDirectoryStream` | 41 of 41 |
| `programmers-tracker:local`, JDK 25.0.4.1, over the macOS bind mount | `fakeowner` | `UnixSecureDirectoryStream` | 41 of 41 |
| the same image, on its own file system | `overlay` | `UnixSecureDirectoryStream` | — |
| Windows | — | not a secure stream | pinned for windows-latest, not run there yet |

All three Unix ones answered alike:

- `.ps` opened through the record root's handle with `NOFOLLOW_LINKS`, when `.ps` is a link: ELOOP;
- an append with `NOFOLLOW_LINKS` to a link: ELOOP; a rename over a link replaces the link;
- a handle held while its directory is moved aside and a link put in its place writes into the moved
  directory; with the directory deleted instead, every create and append fails with ENOENT, and nothing
  lands where the link leads;
- an absolute name leaves the handle behind, and `raw/x` is resolved through whatever stands at `raw`, a
  link included: only single names are safe.

## Options considered

1. **Check again just before each write.** A narrower window, still a window: stat-then-open is the race.
2. **Hold the directory: `SecureDirectoryStream`** — chosen, behind a seam whose second kind holds a
   directory by path, for Windows.
3. **`openat`, `mkdirat` and `renameat` through the FFM API.** It would give `mkdirat`, which the JDK
   lacks. It is restricted native access (`--enable-native-access`), code per platform, and Windows would
   still have nothing like it.

How long a directory is held:

- **per session**, for the raw log: one descriptor per live grading, and none of the opens a frame now
  makes. But a grading the application never ends would hold one for the life of the process, and a
  frame written into a `.ps` deleted under it fails, where reopening at the next frame finds the link and
  holds the frame in memory, as every refusal does;
- **per operation** — chosen: each write opens its directory from the record root and closes it. Its cost
  is measured below.

When a handle is compared with what the checks passed, for a writer that asks git:

- **before git is asked:** a swap while git answers is written into the directory held, which by then is
  moved aside, or deleted, and the write throws;
- **after git has answered** — chosen: the swap is seen, the write refused, said once, and the next write
  asks again.

## Decision

**`DirectoryHandle`** is a directory held open and written in by name: `child`, `isAt`, `create`,
`write`, `append`, `move`, `delete`, a regular file's mode read and set, and `isThereButNotAFile`, which
looks at a name without following a link or opening it, as `Files.exists` does. Two kinds keep one
contract, which `DirectoryHandleTest` runs against both:

- `SecureDirectoryHandle`, over the platform's `SecureDirectoryStream`, never through a link;
- `PathDirectoryHandle`, by path, as every write was before.

**`DirectoryHandles`** chooses: a handle where the file system gives one, the path where it says it does
not. A WARN says so once, when the platform's are chosen, which the composition root's state directory
does at startup. A file system that cannot be asked is taken to give one: a handle is then tried for each
directory, and one whose file system gives none is held by path, so not knowing never costs a handle.
`DirectoryHandleTest` pins that every platform CI runs gives one but Windows.

**`FileReplacement.replace(directory, name, text)`** is #386's one replace, now over a handle: the
temporary file made in the directory held, written, given its mode, moved over the target and taken away
on failure, all by name — a clean-up that fails too kept suppressed on what stopped the write, as #386's
review made it. `replace(target: Path, text)` wraps the target's directory by path, so the records and
every document outside `.ps` are written as before. The temporary file is made with `CREATE_NEW` alone,
as `Files.createTempFile` makes one: `O_EXCL` passes no link, and a zip file system, which #386's pin of a
bare name uses, refuses `NOFOLLOW_LINKS` beside it.

**`StateDirectory`** hands over what it checks:

- `openForWriting()` opens `.ps` through the record root's handle without following a link, runs
  `forWriting()`'s checks and asks git, and hands the handle over only if `.ps`, looked at without
  following a link, is still the directory held. Otherwise it is `Refused(CHANGED)`, transient.
- `open(segments)` is `pathFor` held: each directory made where absent and opened through the one above
  it, without following a link.

**The writers of `.ps`:**

- `AtomicStateFile` under the record repository writes through `openForWriting()`, and `write` says
  whether it wrote. The timers, the backup marker and the seed ledger go through it unchanged.
- `GithubRemote` stores the push credential through `AtomicStateFile.under`, after `forGit()`.
- `FileRawSessionLog` appends, sets runs aside, keeps orphans, releases what it held and discards through
  `open("raw"[, sub])`, once per operation. #401's guards are carried through the handle: an orphan is
  appended, and held orphans released, only where a regular file or nothing stands, and a write that fails
  keeps its frames for the next release. Reads, the work list and the orphans' count among them, stay by
  path.

## Rationale

**A handle is the only thing that removes the window rather than narrowing it.** Every check of a path
holds for one moment. What `openat` gives is a directory the write can name without naming its path
again, so the question "does `.ps` still lead here?" never has to be asked between the check and the
write.

**`NOFOLLOW_LINKS` at every step, one name at a time.** The chain from the record root to the directory
written in is opened one name at a time, each refusing a link (measured: ELOOP). A link at `.ps` or below
refuses the open, exactly as `pathFor`'s stat did, and is refused as `HOLDS_A_LINK`.

**The identity check is what ties the handle to the checks.** `.ps` is opened first and checked by path
after, so a swap between the two could otherwise hand over a directory nobody checked; comparing the
held directory's file key with what `.ps` names, after git has answered, closes that and the git window
at once.

**#386 placed the seam.** Its prediction held for the writers of pages, notes and state documents, which
did not change, and for `FileReplacement`, which did. `RecordBound` was not touched: the records are
still written by path. `AtomicStateFile` changed slightly — `write` now says whether it wrote, which
`GithubRemote` needs, and a guard can only come from `under`, so a guarded document is always in `.ps`.

## Accepted costs

- **Windows keeps N10.** It has no `SecureDirectoryStream`; state is written by path, as before, and the
  startup WARN says so.
- **No `mkdirat`.** A directory absent below a held one is made by path, then opened through the handle.
  If `.ps` is swapped for a link at that moment, the empty directory is made where the link leads; the
  open through the handle does not find it, and nothing is written there. Pinned.
- **A swap after the identity check is written into the directory held.** Between `openForWriting()` and
  the write — microseconds — a swap leaves the write in the directory checked, moved aside, or failing
  where it was deleted. Never where the link leads. A raw frame whose directory is deleted in that moment is
  lost as any frame whose write fails is: the append throws, and the socket reconnects.
- **The raw log's per-frame open checks for links, not identity.** It is `pathFor` held: a different real
  directory put in `.ps`'s place between two frames is written into, as it was. A session's first frame
  runs every check.
- **Reads are by path**, as #387 left them, and the commit side of N10 — git reading the working tree
  again after the searches — is git's, and stays.
- **The records are not held.** `RecordWrites` hands `FileReplacement` a path, wrapped by path. Holding
  them means `RecordBound` walking with `child()` from the real root, and handing the last handle to
  `replace(directory, name, text)`.
- **The cost of a held write**, per operation, each the median of 3,000 calls after 3,000 of warm-up,
  over three runs, through the real classes with nothing tracked:

  | | append a frame | replace a document |
  |---|---|---|
  | host, APFS — main `258ed10` | 29.3–35.0 µs | 190–241 µs |
  | host, APFS — #386 `abea5a9` | 29.7–33.2 µs | 194–212 µs |
  | host, APFS — #374 | 63.4–71.3 µs | 263–317 µs |
  | image over the bind mount — #386 | 49.4–54.8 µs | 577–607 µs |
  | image over the bind mount — #374 | 129.5–146.5 µs | 692–737 µs |

  The two image rows were run back to back. A frame costs about 35 µs more on the host and 85 µs more in
  the image: three directories are opened and closed for each. Opening a directory below a held one
  straight away, and looking at it only when that fails, took the image's append from 181–250 µs to
  158–223 µs in an earlier run. A grading sends a frame per testcase.

## Outcome

#374 on `fix/374-state-through-a-handle`, on main `b5d79f0`. It was built on #386's branch and, unpushed,
moved three times: onto #386 as reviewed (`300a9ad`), where the review's suppressed clean-up, a zip file
system's bare name and #401's guards for orphans were taken in; onto main `4700074`, where #386 landed
(PR #406) with the same tree; and onto #408 (`b5d79f0`), cleanly. #408's two new writes — a replay
adopting the copy a crash left, and a kept copy renamed `unrecorded-<session>` — are in the records tree,
through `RecordWrites`' walk, and stay there: they judge regular files without following a link, and the
rename never overwrites. Neither writes into `.ps`.

- `b70b89a` the seam, its two kinds, and the platform's choice;
- `ac0f60f` #386's one replace, over a handle;
- `9237f7e` `.ps` held for its writers, the push credential among them;
- `64876d8` the raw log through the directory opened for each operation, and `isThereButNotAFile` on the
  handle for #401's guards;
- `321e30f` a held directory's child opened straight away, looked at only when that fails;
- `c24a6de` a rooted name of one segment pinned, after the mutation round;
- `4c4aa9d` a probe that cannot be answered keeps the handle, found reviewing the branch: taken for "no",
  it would have written state by path on Linux and said the platform gives no handle;
- this page, the notes on N10 in #360's page, `SECURITY.md`, the index and progress.

**Red first.** On main `258ed10`, as the Context gives it: the timers document, twice, and the push
credential in `problems/zz`. On #386 (`abea5a9`) the three races `AtomicStateFileTest` now holds failed —
the two that swap while git answers wrote by path and answered true, the one that swaps at the write
never reached its swap. On main `4700074`, with the raw log's races run against handles held by path, as
Windows writes: a frame landed in `problems/zz`, a discard deleted the file there named like the session,
a run set aside was looked for through the link and left behind, and an orphan was not appended — held,
since #401 keeps what a failed write was writing; before #401 that append threw. Against stubs,
`DirectoryHandleTest`'s 41 cases, `FileReplacementTest`'s 4 new ones and 9 of `StateDirectoryTest`'s 10
new ones failed.

**Mutation.** 38 mutants, each run alone against its tests and the file restored after; 37 failed a test
at once. The one that survived dropped the check that a name has no root: every absolute name the test gave
had several segments, which the one-segment check refused first. A rooted name of one segment was added
(`c24a6de`), and the mutant now fails both kinds. The first 33 ran before the move; the two whose code the
move changed (a new file's options, the clean-up) and the five on `isThereButNotAFile` ran after it, on
main. Each count is of failing test cases among the classes run for it, so a lower bound.

| Mutant | Cases failed |
|---|---|
| an append follows a link | 2 |
| a rewrite follows a link | 2 |
| a new file made where something stands | 2, and 2 after the move |
| a name of two segments | 2 |
| a rooted name | survived, then 2 |
| `.`, `..` or an empty name | 2 |
| a handle always at its path | 5 |
| no empty directory replaced through a handle | 1 |
| a move of nothing not answered false, through a handle | 1 |
| a child opened through a link | 3 |
| a child that will not open taken for none | 1 |
| a made child held by path | 2 |
| a new file through a handle in the default mode | 1 |
| the platform's handles by path | 3 |
| the fallback not said | 1 |
| no platform gives a handle | 2 |
| a handle asked for, a path given | 17 |
| `.ps` handed over without asking whether it is the one checked | 3 |
| `.ps` opened by its path | 10 |
| a writer of state handed `.ps` unchecked | 9 |
| a state file written by path though handed `.ps` | 1 |
| the push credential written by path | 1 |
| raw frames written by path though the directory was opened | 3 |
| a failed replace through a handle leaves its temporary file | 3, and 4 after the move |
| no mode kept through a handle | 4 |
| the temporary file not hidden | 1 |
| a child by path follows a link | 1 |
| a move of nothing not answered false, by path | 1 |
| a delete of nothing answered true, through a handle | 1 |
| a link below `.ps` refused as unread | 3 |
| a run set aside not moved | 1 |
| a discard that deletes nothing | 2 |
| a probe that cannot be answered taken for no handle | 1 |
| a link through a handle taken for a regular file | 2 |
| a link by path taken for a regular file | 1 |
| what cannot be looked at through a handle thrown | 1 |
| an orphan appended whatever stands where it goes | 1 |
| held orphans released whatever stands where they go | 1 |

**In the image**, with JUnit's launcher on the final classes and `java.io.tmpdir` on the macOS bind mount:
`DirectoryHandleTest`, `SecureDirectoryHandleTest`, `StateDirectoryTest`, `AtomicStateFileTest`,
`FileReplacementTest` and `FileRawSessionLogTest` ran 212 of 218, and the remote's test 26 of 26. The
others abort on their assumption, as `fakeowner` reads a directory whose modes were taken away: #387's
unreadable document, the directory that will not open, and the name that cannot be looked at.

**Gates** on main `b5d79f0` with this branch, all exit 0 with this page:

- `./scripts/check.sh`;
- `./scripts/test.sh`: 2,639 JUnit tests in 176 classes, 0 failures, 12 skipped; node 4 of 4;
- `./scripts/build.sh`;
- `./gradlew verifyBranchCoverage`: `adapter/store` 87% (769 of 882), `adapter/git` 88% (443 of 498),
  `adapter/config` 65% (25 of 38, at its floor), every package at or above its floor;
- `./scripts/guards.sh`: 12 of 12.

**Not verified.** CI has not run the branch, so the Windows half — no handle given, the fallback said once,
and every contract case by path — has run on macOS and Linux alone, through its by-path kind. Nor live:
a rebuilt server should write the same files with the same modes, and its log should say nothing of handles
on Linux or macOS.
