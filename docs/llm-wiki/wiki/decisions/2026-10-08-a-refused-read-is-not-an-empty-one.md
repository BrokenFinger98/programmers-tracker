---
type: decision
project: programmers-tracker
tags: [security, storage, links, records, mcp]
author: BrokenFinger98
created: 2026-10-08
updated: 2026-10-10
sources: [raw/sessions/2026-10-07-the-readers-that-followed-links.md, raw/sessions/2026-10-08-seventeen-prs-through-one-queue.md]
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
| `FileRawSessionLog.orphans` | `.ps/raw/orphans/<lesson>.jsonl` | yes, the listing and every file's read, with no regular-file check | none here, judged harmless as "only a line count leaves" — **measured false by the review** (below): a link or a FIFO there blocks the boot and every MCP tool. The fix is #378's |
| `FileRawSessionLog.onDisk` | `.ps/raw/<name>` | `exists` follows | none: whether a name is taken |
| `AtomicStateFile.read`: timers, backup marker, `seeds.json` | `.ps/<document>` | no at the document (#360), but a FIFO there was opened and waited on; a linked `.ps` is passed | after the review, a regular file only, looked at without following a link. A linked `.ps` is still passed: git and every state writer refuse a `.ps` that is a link or tracked (#360), and what is read is a number, a date or a hash |
| `PushCredential.stored`, `gitConfig` | `.ps/git-credentials` | no, a regular file read without following (#360) | none |

Outside the records root:

| Reader | Path | Followed a link before | Change |
|---|---|---|---|
| `WatchToken` | the tool's `.ps/watch-token` | yes, read and write; a FIFO there held the server's start | read as a regular file without following a link, written beside and moved, owner-only from creation; a link or a FIFO there is replaced, said. The tool's `.ps` above it is the owner's to place, and is passed |
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
  Kotlin's `lazy` keeps no failure, so this was one property and three getters. The review showed it
  was not enough on its own (below): a holder replaced the `lazy`, read when empty and emptied by an
  append that fails.

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
| ↳ `RecordWriter` | nothing recorded; each grading throws and keeps its frames on the work list. After the review, a grading whose *append* is refused, the history already read, takes nothing either: its copy is withdrawn and the writer's indexes are forgotten, to be read again at the next grading | No attempt number without the history; the first grading after the log reads and appends again is numbered from it. The refused ones wait on the work list for the next start. |
| ↳ `/watch`, every heartbeat (after the review) | the answer stands without `lastRecord`; `recordsUnread` says why in a fixed sentence, and the badge shows it as red `!` | It answered 500 with an ERROR stack every 30 s, and the extension makes the session hand-over on a good answer only. |
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
read error costs one grading's delay, and removing the link needs no restart for the gradings after it.
The ones refused meanwhile wait for the next start, which replays the work list (accepted costs).

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
  frames; MCP fails every call; the vault, statements and pending code wait. Recording resumes at the
  first grading after the link is gone — but, as the review measured, **not for the gradings refused
  meanwhile**: their frames stay on the work list, which only a start replays. Until then MCP omits
  them silently. #377's `sessionsNotReplayed`, merged since, does not cover them: it counts what the
  last start left in place, and these were refused after it. And a grading recorded live before that
  start takes the next number, so the replayed ones are numbered after it, though they came first.
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
- **The state documents under `.ps` are read through a linked `.ps`**, as #360 decided: git and every
  state writer refuse it while it stands, and what is read is a number, a date or a hash — from a
  regular file only, since the review.
- **The orphan count is not harmless, and is not fixed here.** This page judged it so, since only a
  line count leaves: the review measured that premise false. A pulled `.ps/raw/orphans/1.jsonl` linked
  to `/proc/self/fd/1` (committed with `add --force`; `.ps` is ignored only on the victim's side) hung
  the boot in the deployed image right after "Startup reconciliation": no reconcile commit was made,
  and HEALTHCHECK stayed `healthy`. MCP `stats` timed out at 15 s, because `withGaps` counts orphans on
  every tool; the thread dump showed `main` and the MCP carrier in `orphanOf`'s `readAllLines`. A FIFO
  there does the same, and an outside file's lines are counted as orphan frames. `orphans()` follows a
  link at its directory and at each file, and opens each without asking whether it is a regular file:
  the read this page's audit missed. Its fix, the guard and regular-file reads without following a
  link, is #378's, on another branch, since three branches touch `FileRawSessionLog` (critic M3).
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
- **A copy the writer could not take back keeps its number only while the server runs** (the review's
  fix, below). That is the one copy on disk of frames held in memory while `.ps` was refused, or a copy
  whose delete failed. After a start before another grading is recorded, the number is free in the
  log again: the next grading's copy meets the kept one and goes with the runs, as any refused copy
  does. Both are said. *Superseded for frames held in memory by #403, below: their copy is kept under
  another name, and the number is free.*
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

### The review

An adversarial review of PR #398 blocked it. It measured on the APFS host and in the runtime image, a
Linux container over a macOS bind mount running as uid 1000. Its findings, and what this branch did
with each:

- **High-1: the orphan count follows links.** Measured as the accepted costs now say: a link to
  `/proc/self/fd/1` or a FIFO at `.ps/raw/orphans/<lesson>.jsonl` blocks the boot and every MCP tool.
  This page's audit row and accepted cost are corrected. The code fix is #378's, which adds the guard
  and regular-file reads without following a link there.
- **Medium-1: the writer's recovery claim was false when the link appears while the server runs.**
  Measured: g1 took attempt 1; `log/` became a link, and g2 and g3 were refused; the link was removed,
  and g4 took attempt **4**, where this page and the writer's KDoc said 2. `attempts/002.raw.jsonl` and
  `003.raw.jsonl` were left with no record. A restart replayed g2 as 5 and g3 as 6. A restart before any
  new grading numbered them right, but `complete()` met the leftovers, so both records had
  `rawPath=null`. The cause predates #387: the number was allocated and the raw copied before the
  append, and nothing was taken back. **Fixed** (`9f63366`):
  - a failed append withdraws the copy, through the raw log's new `withdraw`;
  - it forgets the writer's indexes — the number, the gap (`sincePrevSec`, found on the way: a retry's
    was measured from the refused grading) and the capture key — which the next grading reads again
    from the log, as a start would;
  - only a regular file is deleted, its directory walked through no link;
  - a copy that must stay keeps its number taken while the server runs, and is said.

  Pinned with the critic's sequence: g4 now takes attempt 2, beside copies 001 and 002 only. A restart
  before any other grading replays g2 and g3 as 2 and 3, each beside its own copy. What stays is in the
  accepted costs: the refused gradings wait for a start, and MCP does not count them meanwhile.
- **Low-1: `/watch` answered 500 on every heartbeat while the log was refused** (`588a52b`). That meant
  an ERROR stack every 30 s, the badge reading "failed — 500 … no detail", and no session hand-over,
  which the extension makes on a good answer only. Now:
  - the answer stands without `lastRecord`;
  - `recordsUnread` says why, in a fixed sentence that quotes nothing of the cause;
  - the badge shows it as red `!`, which the extension README and its twin now describe.

  Only a failed read of the log is answered so; any other failure still fails.
- **Low-2: `AtomicStateFile` read a FIFO** (`af8796b`). One at the tool's `.ps/watch-token` hung the
  `WatchToken` constructor, and the start with it. It now reads a regular file only, looked at without
  following a link, and `WatchToken` says a FIFO it replaces. A linked `.ps` above the document is
  still passed, and the class says why:
  - the records' `.ps` is refused by git and every state writer while it is a link (#360), and holds no
    credential;
  - the tool's own `.ps` is its owner's to place, and `guards.sh` fails the build on anything tracked
    there but its `.gitkeep`.

**Red first.** Against `69249ad`:

- the critic's sequence took attempt 4 where 2 was expected;
- the replay's copies were `null`;
- a failed append's retry took attempt 2, and its gap was 200 s where the log's was 300 s;
- `/watch` threw where it should answer;
- both FIFO tests timed out at 5 s.

The new `withdraw` tests did not compile without the method.

**Mutation.** 18 mutants of the new behaviour, each run against its tests and the file restored after.
17 were killed, two of them only after a pin (`46acd3d`). One survives.

| Mutant | Tests failed |
|---|---|
| nothing forgotten after a failed append | 3 |
| forgotten, but the copy never taken back | 3 |
| a kept copy's number given back | 1 |
| a kept copy not said | 1 |
| frames held in memory not seen | 1 |
| anything at the copy's path deleted | 1 |
| the delete past the walk | 1 |
| a copy already gone counted as kept | 1 |
| a failed take-back counted as done | 1, the pin; it survived before |
| a released key read again from the log | survives (see below) |
| every failed read of the log answered 500 | 1 |
| any failure answered as an unread log | 1 |
| no reason given | 1 |
| the cause quoted | 1 |
| a link checked and a FIFO opened | 2 |
| the look following a link | 1 |
| a name that cannot be looked at, read as absent | 1, the pin; it survived before |
| a replaced FIFO not said | 1 |

The survivor gives the same `IOException` family and leaves the same state. It costs one more read of
the log, and may name the read's refusal where the append's was meant.

**Gates**, all exit 0 at `46acd3d`, with this page:

- `./scripts/check.sh`;
- `./scripts/test.sh`: 2,224 JUnit tests, 18 of them new, 0 failures, 11 skipped as before; node 4 of 4;
- `./scripts/build.sh`;
- `./gradlew verifyBranchCoverage`: `adapter/store` 85% (639 of 746), `adapter/web` 82% (96 of 116),
  `application` 89% (363 of 407), every package at or above its floor;
- `./scripts/guards.sh`.

Still **not verified live**, and not pushed: CI has not run these commits.

### Merged with #377

Main moved to `258ed10` with #377 (`2edfb2a`), which rewrote the raw work list this branch had also
bounded. Merged in `5a86aaa`, with every guarantee of both kept:

- **The listing is #377's.** It asks `forWriting()`, then `pathFor("raw")` after git answers, lists
  regular files alone, never replays a session git has ever tracked, and keeps what it left for
  `sessionsNotReplayed`. That covers everything this page's `listable()` did — the audit row above
  and "nothing listed, said once" in the table of refused reads — so that method and its words went,
  and #377's say it ("Their directory was not listed").
- **The read keeps both.** The replay opens without following a link, as both sides did, and checks
  for a regular file first, as this branch did. Since the listing passes over a link or a FIFO, a
  session that is one is no longer failed at the read: it is left on the work list and counted. The
  check now stands against one swapped in after the listing, which a test pins.
- **The tests of both are kept.** Three of this branch's expected the old place of refusal and were
  changed to the new one: a linked `raw` is said in #377's words, a linked session file gives an
  empty report and stays, and the FIFO test is split in two, passed over through the listing and
  failed, never waited on, when listed before the swap.
- **Mutation, the conflicted code.** The read's regular-file check removed fails the swap test; the
  listing past `pathFor`, non-regular entries listed, no history exclusion, nothing kept for
  `sessionsNotReplayed`, and `withdraw` blind to held frames each fail 1 to 7 tests. The read's open
  following a link survives, race-only as before: the check refuses a link before the open.
- **Gates**, all exit 0 at `5a86aaa`: check; test 2,403 JUnit tests in 169 classes, 0 failures, 11
  skipped, node 4 of 4; build; `verifyBranchCoverage` (`adapter/store` 85%, 659 of 768;
  `adapter/git` 89%; `adapter/web` 82%; `application` 89%); guards, 12 of 12.

### #403: copies left at their number

The adversarial re-check of this branch found two ways, both measured, for a copy to stay at
`attempts/NNN.raw.jsonl` with no record naming it. Each left the next grading given that number with
`rawPath=null`, though the frames survived on disk. Branch `fix/403-raw-copy-collisions`, on #401's head,
with main `9b4dcc1` merged in (`9b7581a`).

- **A crash between the copy and the append** (`305f587`). This can happen on a healthy repository. The
  copy outlived the process. At the next start the replay gave the grading the same number, made the
  same copy, met the one left behind and was refused.
  - `complete()` now points at a regular file already holding exactly the frames it would write.
  - The file is judged without following a link, its directory walked through no link and nothing made
    on the way. It is opened only when its size is theirs, and read no further than one byte past it,
    so a FIFO is never opened and a large file is never read whole.
  - Anything else there is never replaced. The copy is only ever created new, so it is still a
    `FileAlreadyExistsException`.
  - The crash is a clock that fails where the writer stamps the record, after the copy and before the
    append, so none of the writer's clean-up runs. The real reconciler then replays the session.
- **A copy that could not be taken back** (`2edcee7`). This needs an attacked repository: a grading
  refused at its append while `.ps` was refused too. Its frames were held only in memory, so its copy
  was their one copy on disk, and `withdraw` kept it at its number. That was this page's accepted cost
  above, and a restart was enough to make the collision.
  - The copy is now moved, in its own directory, to the raw session's name after `unrecorded-`,
    outside the attempt numbering. `withdraw` answers true, since nothing is left at the number, and
    the writer forgets what it took, as for a copy deleted.
  - The name is the session's, so it is unique per grading. A second refused grading under the same
    number never meets the first, and the move never replaces what already has the name.
  - Anything at the copy's name that is not a regular file is not the log's to move. Its number stays
    taken while the server runs, as before, and the next grading's copy after a restart is refused, as
    any refused copy is.
  - Nothing lists `attempts/`, because the submission log is the one authority for attempts (design
    §4.5). MCP, the problem pages and the repair steps therefore never show the file as an attempt, and
    reconciliation commits it with the rest of `problems/`, so it is not lost either.
  - The WARN names where the file is and what the owner does with it. Moved into `.ps/raw` under the name
    after `unrecorded-`, it is replayed at a start, which a reconciler test pins. Once a record of that
    grading exists, the file can be deleted.
- **Red first.** The crash test recorded the replay with `rawPath=null`, and the restart test recorded
  the next grading the same way. Five raw log tests failed: two refused identical frames with
  `FileAlreadyExistsException`, and three found the copy left at its number. The pins of what was refused
  already, and of the replay the WARN promises, passed with the fix in.
- **Mutation**, against the store, git-history, MCP, application and config tests:

  | Mutant | Tests failed |
  |---|---|
  | no copy already made pointed at | 3 |
  | sizes alone compared | 1 |
  | the bound's walk skipped for the comparison | 1 |
  | a bare log comparing nothing | 1 |
  | the copy kept at its number | 6 |
  | true without the move | 7 |
  | the set-aside not said | 1 |
  | anything at the copy's name moved | 1 |
  | what already has the name replaced | 1 |
  | the move without the bound's walk | 1 |
  | a bare log moving nothing | 1 |
  | one name for every set-aside copy | 5 |
  | the regular-file check of the comparison | none |
  | its size check | none |
  | its read stopping at the size | none |

  The three survivors are each masked by another check the comparison keeps: the regular-file check
  by the size and the no-follow open, the size check by the bounded read, and the read's bound by the
  size check. The last differs only for a file that grows between the two.
- **Gates**, all exit 0 at `2edcee7`:
  - check;
  - test: 2,516 JUnit tests in 172 classes, 0 failures, 11 skipped as before, and node 4 of 4;
  - build;
  - `verifyBranchCoverage`: `adapter/store` 86% (735 of 852), `application` 90% (396 of 439), every
    package at or above its floor;
  - guards: 12 of 12, with this page and progress staged.
- **What remains.** A copy at its number that is not a regular file still keeps the number until a
  restart, and then the next grading's copy goes with the runs. That needs something planted where the
  server had just written its own copy. Not verified live.
