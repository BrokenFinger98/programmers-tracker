---
type: decision
project: programmers-tracker
tags: [security, storage, links, records, mcp]
author: BrokenFinger98
created: 2026-10-08
updated: 2026-10-08
sources: [raw/sessions/2026-10-07-the-readers-that-followed-links.md]
---

# A refused read is not an empty one

## Context

#361 made every writer in the records repository refuse to follow a link
([[decisions/2026-10-08-no-writer-follows-a-link]]). Its two reviews left the readers: some outside
`problems/`, which #354 had bounded, still followed one (S5, Q7), and the `/watch` token was written
in a way that followed one (S4). #387 collected them:

1. `JsonlRecordStore.read` read `log/submissions.jsonl` through a link, `RunLog`'s duplicate check
   read `runs.jsonl` through one, and the seed ledger hashed a seed through one. None copied what it
   read into a tracked file, but the read side and the write side disagreed: a linked `log/` stopped
   recording, since #361 refused every append, while its lines were still served as the log.
2. `WatchToken` wrote `.ps/watch-token` with `writeText` and narrowed it to `rw-------` afterwards, so
   the token sat in a file others could read until then, and the write followed a link. This `.ps` is
   the tool's own, beside the checkout, not the records repository's.
3. A Windows junction was said to be refused by #361's real-path comparison, and no test had made one.

### The audit

Before any code, every read in `src/main` was listed: grep for `readText`, `readLines`,
`readAllBytes`, `readAllLines`, `readString`, `newBufferedReader`, `newInputStream`, `Files.lines`,
`useLines`, `newByteChannel`, `exists`, `isRegularFile` and `isDirectory` used as a gate, and directory
listings (`Files.list`, `newDirectoryStream`), on branch `fix/387-reads-never-follow-links`. "Before"
is `41f713e`, main with #354, #360 and #361.

Under the records root, outside `problems/` and `.ps`:

| Reader | Path | Followed a link before | Change |
|---|---|---|---|
| `JsonlRecordStore.read` | `log/submissions.jsonl` | yes, at `log/` and at the file; a dangling link and a `log/` that could not be searched read as a log not written yet | through `RecordReads`; a refusal is thrown |
| `VaultDashboard.seed`, `adopted` | `dashboard.base`, `README.md`, `README.ko.md` | only in the race after its own link check; a FIFO there blocked the boot | one read per seed, through `RecordReads`; a refusal leaves the seed alone, said |
| `SeedLedger.isUnchanged` | the same seeds | yes, `readString` on the path it was handed | reads nothing: it hashes the bytes `VaultDashboard` read |
| `RepositoryHeartbeat.read` | the marker in `.git/`, or at the root | no, read without following a link (#361) | none |
| `RecordRepositoryIgnores` | `.gitignore` | no, read without following a link (#360) | none |
| `RecordRepositoryLock.gitDirectoryOf` | `.git`, a worktree's pointer file | yes | none: git checks out nothing named `.git`, so no clone or pull puts a link there, and the pointer only says where git keeps its directory |
| `RecordWriter.pathsOf` | the log and the raw copy | `exists` follows | none: a gate for `git add`, which adds a link as a link (#354) |
| `CommandLineGitSync.operationInProgress` | git's own state files | `exists` follows | none: git's own directory |
| `TrackedStateEntries`, the root's real-path comparisons | index entries, the root | follow on purpose | none: `isSameFile` is how #360 catches an alias, and the root is configuration |

Under `problems/`:

| Reader | Path | Followed a link before | Change |
|---|---|---|---|
| `FileProblemStatements.of`, and `ProblemReadme` through it | `statement.md` | no, `ProblemFiles` (#354) | none, confirmed |
| `CodeArtifacts.readAttempt` | `attempts/NNN.<ext>` | no, `ProblemFiles` | none, confirmed |
| `FileGradingCodes.submitted`, `runs` | attempts, `runs.jsonl` | no, `ProblemFiles` | none, confirmed |
| `FileDerivedArtifacts.examplesOf` | `examples.json` | no, `ProblemFiles` | none, confirmed |
| `RunLog.alreadyHolds` | `runs.jsonl` | yes | through `ProblemFiles`; a refusal reads as no line held, and the append's own bound then refuses and throws |
| `RecordWrites.healed` | an appended file's last byte | no, after the walk, without following | none |
| `RecordLayout.existingProblemDirectory` | the listing of `problems/` | yes | none: names only (#354), and every reader and writer handed the directory goes through its bound |
| `FileRawSessionLog.complete` | `attempts/NNN.raw.jsonl` | `exists` follows | none: a gate before `createNew`, which creates nothing where anything stands |

Under `.ps` in the records repository, #360's state directory:

| Reader | Path | Followed a link before | Change |
|---|---|---|---|
| `FileRawSessionLog.unprocessed` and `RawSessionReconciler.linesOf` | `.ps/raw/<session>.jsonl` | yes, the listing and the replay | the listing goes through `StateDirectory.pathFor`; the replay reads only a regular file, without following a link. Not in the issue: what replays becomes a record that is committed and pushed |
| `FileRawSessionLog.framesOnDisk` | the same | no, the checked raw directory and a no-follow open (#360) | none |
| `FileRawSessionLog.orphans` | `.ps/raw/orphans/<lesson>.jsonl` | yes | none: what leaves is a lesson id from the name and a count of lines, never content |
| `FileRawSessionLog.onDisk` | `.ps/raw/<name>` | `exists` follows | none: whether a name is taken |
| `AtomicStateFile.read`: timers, backup marker, `seeds.json` | `.ps/<document>` | no at the document (#360); a linked `.ps` is passed | none: git and every state writer refuse a `.ps` that is a link or tracked (#360), and what is read is a number, a date or a hash |
| `PushCredential.stored`, `gitConfig` | `.ps/git-credentials` | no, a regular file read without following (#360) | none |

Outside the records root:

| Reader | Path | Followed a link before | Change |
|---|---|---|---|
| `WatchToken` | the tool's `.ps/watch-token` | yes, read and write | read without following a link, written beside and moved, owner-only from creation; a link there is replaced, said |
| `ManualFileSessionProvider.readValue` | the tool's `.ps/session` | yes | none: outside the records, in a `.ps/` this repository ignores and `guards.sh` fails the build on |
| `ClasspathProblemCatalog`, `VaultDashboard.shipped` | the classpath | — | none |
| `GitProcess.textOf` | the system temp directory | — | none |

The issue named three readers. The audit found two more, and a way of reading wrong that several of
them shared:

- **The work list's replay.** At boot `RawSessionReconciler` replays `.ps/raw`, the sessions whose
  records never landed, and what replays becomes a record. A session file or a `raw` directory that was
  a link made a record of whatever it led to (red below).
- **`VaultDashboard`'s own reads of a seed**, up to three beside the ledger's. Its link check stood in
  front of them only outside a race, and a FIFO at a seed held `ensure()` past a five-second timeout.
- **"Cannot tell" read as "not there".** `Files.exists` and `isRegularFile` answer false for a name
  under a directory that cannot be searched, so a `log/` without its search bit read as a log not
  written yet: no submissions.

## Options considered

Where the read bound lives:

1. **`ProblemFiles`' real-path bound, generalized to allow-listed first names at the root, with the
   listing check.** It follows a link that stays inside its bound, which #354 decided is harmless for
   a read under `problems/`. At the root it is the disagreement the issue names: a
   `log/submissions.jsonl` linked to `log/old.jsonl` is read while every append to it is refused.
2. **A third copy of the walk for reads.** Every check #361 took a review round to get right — the
   listing for the image, NFC for HFS+, the real path for Windows — copied once more.
3. **Read methods on `RecordWrites`.** No copy, but a writer that reads, whose refusals say "Not writing"
   and throw `RefusedWriteException`.
4. **The walk taken out of `RecordWrites` into `RecordBound`**, shared by the writer and a reader,
   `RecordReads` — chosen. `RecordWritesTest` passed unchanged across the move.

What a refused read of the log means:

- (a) **A WARN plus an empty read**, as `ProblemFiles` answers under `problems/`. Every MCP tool would
  say "no submissions". `incompleteHistory` could not say otherwise: it counts orphaned frames, not a log
  that was never read. At boot the vault refresh would rewrite the index from nothing, and the tag notes
  with zero counts, and the next pass would push them. The writer would number gradings from nothing.
- (b) **A failure everywhere, the boot included.** The writer read the log when it was built, so the
  server would not start: a crash loop for as long as the link stood, with no grading observed in that
  time. #361 kept a standing refusal from crash-looping the container, and the lock refusal is "the one
  failure this tool deliberately chooses over degrading" (`RecordRepositoryLockedFailureAnalyzer`).
- (c) **A failure that reaches every reader of the history, and a boot that goes on** — chosen.

How the writer outlives a log it cannot read:

- (i) Read at construction, as it was — (b) above.
- (ii) An empty history when the read fails. Nothing is appended while the link stands, since the
  bounds agree; but once it is gone the next grading is numbered from nothing, takes an attempt number
  already used, and `CodeArtifacts` replaces that attempt's code.
- (iii) Sealed until a restart. Safe, but a transient I/O error would then stop all recording until
  someone restarted the server, where the crash it replaces restarted itself.
- (iv) **Read when the first grading needs it, and again at the next while it fails** — chosen.
  Kotlin's `lazy` keeps no failure, so this is one property and three getters.

## Decision

**`adapter/store/RecordBound`** holds the bound and the walk #361 wrote: below the root as configured,
no `.` or `..`, the allow-listed first names, and every directory a real one, listed by its parent under
exactly the name walked and resolving to the path walked. It answers the file in its walked directory,
null when a directory on the way is not there, or `OutOfBounds` with the reason. Whether anything is
there is asked of the filesystem, and only "no such file" is no. `RecordWrites` turns `OutOfBounds`
into its refusal as before.

**`adapter/store/RecordReads`** reads a file a writer keeps at the root through that walk, with nothing
made on the way. Nothing there is null and silent. Anything else is refused — a link, dangling or not,
a directory, a FIFO, which is never opened — said once per reason, naming the path and the part at
fault and never where a link leads, and thrown as `RefusedReadException`. A regular file is opened
without following a link.

What a refused read means, reader by reader:

| Reader | A refused read | Why |
|---|---|---|
| `JsonlRecordStore.read` | thrown | The record of record: an empty answer is every reader's "no submissions". |
| ↳ MCP, every tool | a fault of ours, answered as JSON-RPC `-32603` on HTTP 200 (#355) | No count at all, rather than counts from behind a link or a zero from a log never read. |
| ↳ `RecordWriter` | nothing recorded; each grading throws and keeps its frames on the work list | No attempt number without the history; the first grading after the log reads again is numbered from it. |
| ↳ boot: the code attachment pass | said by its kind, nothing retried, and the boot goes on | The startup runner catches nothing. |
| ↳ boot: the vault refresh, the statement backfill | said, as any failure there already was | Nothing is rewritten from an empty history. |
| `RunLog`'s duplicate check | no line held | The append that follows refuses the link and throws, so the record keeps its code pending. |
| `VaultDashboard`'s seed read | the seed is left alone, said | A seed is the reader's, and nothing is written over what could not be read. |
| `SeedLedger` | — | It reads no seed: it hashes the bytes `VaultDashboard` read. |
| `FileRawSessionLog.unprocessed` | nothing listed, said once | What waits behind a link is replayed by a boot that can list it. |
| `RawSessionReconciler`'s replay | the session fails, said, and stays on the work list | As any session that cannot be settled. |
| `WatchToken`'s read | no token: a new one replaces the link, said | A credential is never taken from where a link leads. |

**`WatchToken`** reads and writes through `AtomicStateFile`, as the push credential does: a temporary
file beside the target, created owner-only, moved over it. That makes `adapter/web` import
`adapter/store`, an edge `docs/development-rules.md` §1 now lists, rather than a fourth copy of the
temp-and-move write before #386 merges the three there are. On Windows, with no POSIX permissions, the
file gets what its directory gives a new one, as before.

**Junctions** are made with `cmd /c mklink /J`, which needs no privilege, in two tests enabled on
Windows alone: one for the write bound, which the issue asks for, and one for the read bound, which
shares the walk. The fixture fails rather than skips where it cannot make one.

## Rationale

**One walk, so the two sides cannot drift.** A reader that agrees with its writer by sharing the code
needs no argument about where a link may lead: it refuses what the writer refuses, the listing for the
image and the real path for Windows included, because they are the same lines.

**Absent is not zero.** The MCP instructions tell a model that a missing value is unknown, not zero.
A log that could not be read and answered as empty breaks that at the root of every count, and nothing
downstream can tell the two apart. Thrown, it fails where it is asked, and the server log says why.

**Degrade, do not crash.** The server keeps observing while the log is refused, so each grading's frames
wait on the work list and nothing is lost. The writer retries the history at each grading, so a transient
read error costs one grading's delay, and removing the link needs no restart.

**Each change was red first, against the code before it** (saved per cycle):

- `RecordBoundTest` and `RecordReadsTest` did not compile without their classes. With `Files.exists` in
  `RecordReads`, the two unsearchable-directory cases read as absent.
- `RecordWriterTest`: the store double's `IOException` escaped `RecordWriter.of` before any grading.
- `CodeAttachmentTest`: it escaped `attachPending`.
- `JsonlRecordStoreTest`, against the old read:
  - a linked log and a linked `log/` returned another log's record;
  - a dangling link and an unsearchable `log/` returned an empty history.
- `McpToolInvokerTest`: `submissions` answered with counts from behind a link.
- `StartupReconciliationTest`: the boot never said it could not read the log.
- `RunLogTest`: the append behind a link threw nothing.
- `SeedLedgerTest` did not compile against a ledger that took a path. In `VaultDashboardTest`, a FIFO at
  `dashboard.base` held `ensure()` past the five-second timeout.
- `FileRawSessionLogTest`: the listing returned the session behind a linked `raw`.
- `RawSessionReconcilerTest`: the reconciler recorded a grading read through a linked session file.
- `WatchTokenTest`: a blank token file's second name received the token, and a link and a dangling link
  at the target were followed.

Two tests were pins, green before: the owner-only mode of a generated token, and the replay's FIFO check
(below).

## Accepted costs

- **An MCP client is told nothing more than "the server failed".** The fault carries no message by
  design (#355), so a model cannot say that the log is a link; the server log says it once. A message
  naming the cause would need `adapter/store` to know MCP's errors, or a fault type in `application`.
- **A linked log means a running server that records nothing.** Each grading is an ERROR and keeps its
  frames; MCP fails every call; the vault, statements and pending code wait. All of it resumes at the
  first grading after the link is gone.
- **An unreadable log no longer stops the boot.** It used to fail the writer's bean, and the container
  restarted. Now every reader says it, and the writer retries at each grading. A transient error at boot
  costs that boot's attachment pass and page refresh, which wait for the next; a standing one no longer
  crash-loops.
- **A hard link at the log is read and not appended to.** It is the file itself, read as #354 accepted
  for reads; the append refuses a second name (#361). Each grading then fails, said, with its frames kept.
  Git cannot deliver one.
- **Every check is of a path at one moment.** Two mutants survive for that reason alone: the read's open
  without `NOFOLLOW_LINKS`, and the replay's. Each matters only if a link is swapped in between the look
  and the open, by something racing the server on this machine.
- **A link that stays inside `problems/` is still read by `RunLog`'s check**, as by every reader there
  (#354). The append refuses it, so the record keeps its code pending.
- **Other reads under `.ps` pass a linked `.ps` or `raw/orphans`.** The state documents and the orphan
  count read through such a directory, as #360 decided: git and every state writer refuse it while it
  stands, and what is read is a number, a date, a hash or a line count.
- **A watch token file linked on purpose is replaced by a new token**, which the extension must be
  given. Pointing `tracker.watch.token-file` at the file itself keeps a token elsewhere.
- **No test watches the window the narrowing left open.** The narrowing is gone, so the window cannot
  open; seeing one would take a seam into the filesystem. MockK cannot intercept `java.nio.file.Files`
  here: kotlin-reflect fails to initialize under it (measured with this build's JDK 25 toolchain). The
  owner-only mode, a blank file's second name and the link cases are pinned on the result.
- **A seed that is not UTF-8 now reads as edited** and is left alone silently. Its strict decode used to
  fail the seed with a WARN; either way nothing was written.
- **Every read at the root walks**: a stat, a listing and a real path per directory, as a write does.
  `log/` is one directory deep. Not measured for reads; #361 measured the listing at writes.
- **The junction tests have not run.** They need windows-latest, and this branch has not been pushed.
  What they exercise is pinned on every platform by the `DiskAnswers` cases that play a real path
  leading elsewhere.

## Outcome

#387 on `fix/387-reads-never-follow-links`, from `41f713e`:

- `723b544` the walk out of `RecordWrites` into `RecordBound`, its own tests;
- `828fd0d` `RecordReads`, and existence asked of the filesystem;
- `af64882` the writer's history read when the first grading needs it;
- `8295461` the attachment pass outlives a log it cannot read;
- `23275fc` the log read through no link; MCP and boot pins;
- `7098d45` `RunLog`'s check through `ProblemFiles`;
- `874d731` each seed read once, through the bound; the ledger hashes bytes;
- `d2bc06c` the work list listed and replayed through no link;
- `6137b75` the watch token written beside and moved; the adapter edge;
- `2fe85b7` the junction tests;
- `3235b97` the replay's FIFO pin, from the mutation round;
- this page, #354's and #361's notes, `SECURITY.md`, `docs/mcp.md` and its twin, the index and progress,
  in the commit after them.

**Mutation.** 29 mutants, each run against its tests and the file restored after. 27 were killed, one of
them only after the FIFO pin. Two survive, both race-only.

| Mutant | Tests failed |
|---|---|
| a read that bypasses the walk | 7 |
| no regular-file check before the open | 7 |
| a refusal not said | 4 |
| a refusal said every time | 1 |
| the open following a link | survives: race only |
| "cannot tell" read as absent, at the file | 2 |
| "cannot tell" read as absent, in the walk | 1 |
| "no such file" not caught as absent | 12 |
| a refusal answered as absent: option (a) | 19 |
| the log read through the old raw calls | 6 |
| the writer's history read at construction | 2 |
| an unreadable history taken as empty: option (ii) | 1 |
| the attachment pass without its catch | 2 |
| the unread log not said | 2 |
| the unread log said with its message | 1 |
| `RunLog`'s check through raw calls | 1 |
| seeds read through raw calls | 1 |
| the seed flow without adoption | 1 |
| the work list listed past the guard | 1 |
| the unlisted work list not said | 1 |
| the replay through raw calls | 1 |
| the replay without its regular-file check | 1, the FIFO pin; it survived before |
| the replay's open following a link | survives: race only |
| the token written in place and narrowed | 3 |
| the token read through a link | 1 |
| a replaced token link not said | 1 |
| the replaced file's mode kept | 1 |
| a writer passing the bound's answer on unsaid | 21 |
| a read making the directories it walks | 1 |

**Gates**, all exit 0 at `3235b97`:

- `./scripts/check.sh`;
- `./scripts/test.sh`: 2,178 JUnit tests, 43 of them new, 0 failures, 11 skipped — the 9 as before,
  plus the two junction tests off Windows; node 4 of 4;
- `./scripts/build.sh`;
- `./gradlew verifyBranchCoverage`: `adapter/store` 85% (625 of 732), `adapter/web` 80% (82 of 102),
  `application` 88% (344 of 387), every package at or above its floor;
- `./scripts/guards.sh`, with this page.

`RecordReads` covers all 6 of its branches. `RecordBound` misses 4 of its 46 and `RecordWrites` 6 of its
52: 10 of the 98 they hold together, where #361 recorded 12 of 98 in `RecordWrites` alone.

**Not verified live**, and CI has not run the branch. A normal records repository should see nothing
change: the same answers from every MCP tool, the same pages, and no `Not reading` line at boot. The
junction tests run for the first time on windows-latest.
