---
type: decision
project: programmers-tracker
tags: [security, storage, links, records]
author: BrokenFinger98
created: 2026-10-08
updated: 2026-10-08
sources: [raw/sessions/2026-10-07-the-readers-that-followed-links.md]
---

# No writer follows a link

## Context

The records repository root holds, beside `problems/`, the push token at `.ps/git-credentials` and
the raw frames. Git stores symbolic links (mode `120000`, measured in #354), so a clone or a pull can
put one anywhere in the repository. #354 bounded every reader under `problems/`
([[decisions/2026-10-07-no-reader-follows-a-link-out-of-problems]]). #360 bounded the writers of
`.ps` and the `.gitignore` ([[decisions/2026-10-08-reconcile-never-stages-the-state-directory]]).
The other writers followed links. #354's review measured three cases in a scratch copy
(raw/sessions/2026-10-07-the-readers-that-followed-links.md):

- writing a problem's `README.md` that was a link to `.ps/git-credentials` overwrote the credential
  file;
- appending to a `runs.jsonl` that was a link appended a run's line to it;
- a dangling `statement.md` link made the statement writer create a file outside `problems/`.

The issue (#361) named six writers: `ProblemReadme.write`, `RunLog.append`, `writeStatement`,
`FileExampleStore`, `writeRunner` and `ProblemIndex`. Its comment added a route. A refused
`examples.json` makes the runner say "press Run Code". Pressing it runs `FileExampleStore`'s write,
which went through the same link and overwrote what it pointed at. A planted link let the server
overwrite, append to or create any file the owner can write, with content drawn partly from the
records. That is an integrity exposure rather than a leak, and the token's own file was in reach.

### The audit

Before any code, every call in `src/main` that writes, creates, moves or deletes was listed: grep
for `Files.write`, `writeString`, `newOutputStream`, `newBufferedWriter`, `createDirectories`,
`createFile`, `move`, `copy`, `APPEND`, `appendText`, `deleteIfExists` and `AtomicStateFile`
(branch `fix/361-writers-never-follow-links`). The table lists what a link could redirect before
this change. "On the way" means a directory between the root and the file.

| Writer | Path | How it wrote | A link at the file | A link on the way | What it writes |
|---|---|---|---|---|---|
| `ProblemReadme.write` | `problems/<id>/README.md` | `createDirectories`, then `writeString` (truncate) | written through: overwrote the token (measured) | written through | the page, the statement inlined |
| `ProblemIndex.write` | `problems/README.md` | the same | written through | written through (`problems` itself) | the index |
| `CodeArtifacts.writeLatest` | `problems/<id>/Solution.<ext>` | `AtomicStateFile`: temp file, then rename | replaced (the rename) | the temp file and the rename landed where it led; not in the issue | fetched code |
| `CodeArtifacts.writeAttempt` | `problems/<id>/attempts/NNN.<ext>` | the same | replaced | the same; not in the issue | a submit's code |
| `RunLog.append` | `problems/<id>/runs.jsonl` | `CREATE`, `APPEND` | appended through: appended to the token (measured) | appended through | a run's line, with its code |
| `FileExampleStore.replace` | `problems/<id>/examples.json` | `writeString` | written through (the comment's route) | written through | captured examples |
| `writeStatement` | `problems/<id>/statement.md` | `exists` (follows), then `writeString` | to a file: nothing written, counted filled, fetched again at every boot; dangling: created its target (measured) | written through | the fetched statement |
| `writeRunner` | `problems/<id>/RunnerTest.java`, `runner_test.*` | `writeString` | written through | written through, also through a link that stays inside `problems/` | the runner, example values in it |
| `writeRunner`'s refusal sweep | the eight runner names | `deleteIfExists` | the link itself deleted | **files of those names deleted wherever it led**; not in the issue | — |
| `FileRawSessionLog.complete` | `problems/<id>/attempts/NNN.raw.jsonl` | `createDirectories`, then `CREATE_NEW` | refused already (`O_EXCL` fails on any link) | the copy landed where it led; not in the issue | a submit's raw frames |
| `JsonlRecordStore.append` | `log/submissions.jsonl` | `CREATE`, `APPEND` | appended through; not in the issue | appended through (`log`) | the record line |
| `TagNotes.write` | `tags/<slug>.md` | `writeString` | written through; not in the issue | written through (`tags`) | counts and links |
| `VaultDashboard` seeds | `dashboard.base`, `README.md`, `README.ko.md` | `writeString` when absent, or unchanged by the ledger | written through when the ledger matched the target's bytes; dangling: created its target; not in the issue | none: the seeds sit at the root | the shipped seed |
| `RepositoryHeartbeat` | `.git/.programmers-tracker.alive`, or the root when `.git` is not a directory | `CREATE`, `WRITE`, `TRUNCATE_EXISTING` every 4 s | truncated its target at every beat, root case only; the read followed it too; not in the issue | none: its own directory | a liveness token |

Already bounded by #360, unchanged: the state files written by `AtomicStateFile.under` (the timers,
the backup marker, the seed ledger), the credential store, the raw log's appends, moves and deletes
under `.ps/raw`, and `RecordRepositoryIgnores`' `.gitignore`.

Not exposed, unchanged:

- **`RecordRepositoryLock`** writes into `.git/`, or into the linked worktree's git directory.
  Git checks out nothing under `.git`, so no clone or pull puts a link there. It writes at the root
  only when the root has no `.git` at all, and nothing has been pulled into a directory that is no
  repository.
- **Its stale-lock removal and the heartbeat's `close()`** delete a link itself, never what it names.
- **`RecordRepositoryInit`** creates the root itself, which is configuration and may be a link.
- **Git's own writes** go through `add` and `commit`.

Outside the records root, unchanged: `WatchToken` and `ManualFileSessionProvider` write in the tool's
own `.ps/`, and `GitProcess` writes in the system temp directory.

The issue named six writers. Eight more were found: the code files and the raw copy (a link on the
way, which the rename and `CREATE_NEW` never covered), the submission log, the tag notes, the seeds,
the heartbeat marker, and the runner sweep. The sweep was the only *delete* through a link.

## Options considered

1. **Copy a check into each writer.** That makes fourteen copies of a check that took #353 and #354
   three rounds to get right for reads. Each new writer would be one more chance to get it wrong.
2. **Mirror the read bound: resolve the target's real path, check that it lies under
   `<real root>/problems`, then write.** The issue suggested this direction. Two things are wrong
   with it for writes. `createDirectories` runs before the check, so a write that
   creates `problems/<id>/attempts` creates it through a linked `problems/<id>`. And the read bound
   accepts a link that stays inside `problems/`, so a write to the resolved path would follow a
   `problems/2-y` linked to `problems/1-x` and write into the other problem's directory.
3. **Refuse a link at the file, for every writer.** It is simple, and it fails closed. But it leaves
   the link in place, so every later grading and every boot refuses again. The reader keeps reading
   a linked statement as absent, and the backfill fetches it at every boot. For a file the server
   owns, replacing the link heals the repository once. Kept for the two append-only logs: a link
   there stands for history that a replacement would drop or invent. Kept in spirit for the seeds,
   which are the reader's.
4. **Handle-based writes (`SecureDirectoryStream`, `openat`).** These close the time-of-check gap
   between a walk and a write. They are not available on Windows, which CI runs. #360 already
   lists them as a follow-up for the state files, so they wait for that, for every writer at once.
5. **One failure posture for all writers.** If every writer threw, a refused problem page would end
   `refreshVault` at boot, and every page after it, the index and the tag map would never be
   rewritten. If every writer skipped, a record could name code that was never written. Chosen:
   each writer keeps the posture it had, decided per writer below.
6. **Where the boot pass absorbs a failed attachment.** Catching around `attachPending` in
   `StartupReconciliation` would end the pass at the first failure. The pass runs oldest first, so
   one refused record would block every record behind it, at every boot. Chosen: per record, in
   `CodeAttachment`.

## Decision

**`adapter/store/RecordWrites`**, internal and beside `ProblemFiles`, is the one way the records
repository is written. A writer gets one with `underProblems(layout)` or
`underRoot(root, firstNames)`. Parts of this were amended after the review round (Outcome).

- **The walk.** Every directory from the real root down to the target's own is walked one name at a
  time, and each is created where absent. Each must be a real directory, not a link
  (`NOFOLLOW_LINKS`). Its parent must list it under exactly the name walked, and its real path must
  be the path walked. Both are compared as text after NFC. Unlike a read, a write follows no link at
  all, even one that stays inside the bound. A folded alias such as `Problems`, or a directory that
  resolves elsewhere such as a Windows junction, is refused before anything is made inside it. Real
  paths and listings come through `DiskAnswers`, a seam like `StateDirectory`'s listing.
- **The bound.** The target must lie below the root as configured and name no `.` or `..`. A
  problem's files lie inside `problems/`. A writer at the root's own level keeps an allow-list of
  first names: `log`, `tags`, the seeds and the heartbeat's marker, so never `.git` or `.ps`. Only the
  root is resolved, physically and from the path as configured, as git and the lock resolve it.
- **The file.**
  - `replace` writes a temporary file beside the target and moves it over the target. A link
    standing there is replaced, never written through, and said once. It keeps the mode of the
    regular file it replaces. A new file gets what a plain write gives one, unless the writer asked
    for owner-only.
  - `writeOnce` leaves a regular file alone and replaces anything else.
  - `appendLine` requires a regular file or none, ends a line a crash cut short, and opens without
    following a link. Where the `unix` view can count a file's names, it refuses a file with a second
    name, a hard link. Windows offers no such view.
  - `createNew` creates nothing where anything stands.
  - `deleteIn` creates nothing, and deletes nothing through a link.
- **A refusal.** It logs one WARN per reason for the instance. The WARN names the path the writer was
  handed and the part of it at fault, never the content and never where a link leads. Then the
  refusal is thrown as `RefusedWriteException`, unless the writer asked to skip (`replaceOrSkip`).

Each writer's posture:

| Writer | Posture | Why |
|---|---|---|
| `CodeArtifacts` (code files) | throws; owner-only, as since #18 | A record must never name code that was not written. The attachment fails, the record keeps `codePending`, and the next boot retries it. |
| `RunLog` | throws | The same, for a run. |
| `FileRawSessionLog.complete` | throws | `RecordWriter` already keeps the frames with the runs when the copy fails. |
| `writeStatement` | throws | The backfill counts the problem as not filled, which it was not. Attachment never reaches it with a refused directory, because the code files share that directory and fail first. |
| `JsonlRecordStore` | throws | Nothing is appended where a link leads. The live path logs `settled but was not recorded; its frames are kept`. The frames stay on the work list to replay once the link is gone. |
| `ProblemReadme`, `ProblemIndex`, `TagNotes` | WARN, skipped | Derived from the log and rewritten at every attachment and boot. A throw would take every later page, the index and the tag map down with it. |
| `FileExampleStore`, the runner and its sweep | WARN, skipped | They were best effort before. |
| `VaultDashboard` | a link at a seed is left alone and said; otherwise written through `replaceOrSkip` | A seed is the reader's, and a link is something someone made. |
| `RepositoryHeartbeat` | WARN, skipped; read without following a link | It was best effort before. A link where the marker should be reads as no marker, and the next write replaces it. |

One change in `application`: `CodeAttachment.attachPending` treats a record whose attachment throws
as `DEFERRED` and goes on to the next record. An I/O failure, a refused write among them, is said by
its class name alone, because its message can carry a path that names where a link leads. Any other
fault is an ERROR with its stack. Cancellation is rethrown. At capture time `ChannelCapture` has
always caught the same failure. At boot nothing did:
`StartupReconciliation` calls the pass unwrapped, and the startup runner is unwrapped too. #354's
accepted costs traced this from the code. A refusal that stands until someone removes the link would
have made every restart end the same way.

## Rationale

**A write that never passes a link needs no notion of where the link leads.** The read bound asks
"is the real path inside?" because a read through an inside link is harmless. A write through any
link changes a file the writer was not handed. Walking name by name with `NOFOLLOW_LINKS` answers
the only question a write needs, before anything is created. The parent's listing and the real path
add the cases no link check sees: a folded alias and a junction. Neither answer holds everywhere on
its own. The tracker's image echoes the name asked for as the real path, and Windows' `Path.equals`
ignores case. So both are asked, and each is compared as text after NFC, which HFS+ needs.

**Replacing heals; refusing repeats.** Git commits a replaced link as a typechange to a regular file,
which is what the repository should hold. The statement shows the difference. Refused, a linked
`statement.md` read as absent and was fetched again at every boot (#354's accepted cost). Replaced, it
is written once, read normally from then on, and the backfill's count becomes true.

**Each test was red first, against the old code.**

- The helper's first 32 tests: 20 failed against a stub making the old writers' raw `Files` calls.
  The other 12 pin behaviour that was already right. Two more came from the mutation review.
- The writer tests: every new one failed against the unchanged writers, on the exposure itself. The
  token's file was overwritten or appended to, files appeared outside, and stale-runner names were
  deleted outside. Two exceptions pin what already held: code files stay owner-only, and a dangling
  link where the raw copy goes was refused already.
- Two were red only on the warning, because the rename never wrote through a link at the file:
  `CodeArtifacts`' linked and dangling files.
- The boot pass: `attachPending` threw the double's `IOException` out before the change.
- The heartbeat's read: a marker linked to a file that changed during the watch made
  `claim()` throw `RecordRepositoryLockedException` against the old read.

**Each guard is load-bearing.** The mutation results are under Outcome.

## Accepted costs

- **Every check is of a path at one moment.** A directory swapped for a link between the walk and the
  write is followed. So is one swapped between creating the temporary file and moving it. A FIFO
  swapped in after the append's regular-file check would block the append. Each needs a process
  racing the server on this machine, and the server never pulls. Handle-based writes are the
  follow-up #360 already lists. The append's `NOFOLLOW_LINKS` matters only inside that race; its
  mutant survives.
- **A hard link at an appended file is appended to on Windows.** Elsewhere it is refused, since the
  review round. Windows has no `unix` view to count a file's names. Git cannot deliver a hard link,
  and a replaced file breaks one rather than writing through it.
- **Anything linked on purpose on the way is refused.** That covers `problems/`, a problem
  directory, `attempts/`, `tags/` and `log/`, even a link that stays inside `problems/`, which the
  reader follows. No code, page, log line or note is written under it, and each refusal is said once
  per writer per process.
- **A link the owner made at a file the server owns is replaced** at the next write, and git then
  shows a typechange. This covers a problem page, a code file, the examples, the statement and a
  runner. It is said once per path. A seed that is a link is left alone instead.
- **A linked `log/submissions.jsonl` stops recording.** Every grading throws and stays on the work
  list until the link is gone. The live path logs an ERROR for each. The copy beside the record is
  made before the append (#95's order), so each such submit leaves an `attempts/NNN.raw.jsonl` that no
  record names. The attempt counter also moves on in memory. Both were already true of any failing
  append.
- **A failed attachment at boot is retried at every boot.** That is one fetch per boot per such
  record, as for any pending record. The same holds for the statement of a problem whose directory is
  refused, within the backfill's cap.
- **A replaced file is a new file.** Its owner is the server's user, a hard link to it is broken, and
  extended attributes are not carried. Its mode is kept. The heartbeat now writes its marker by
  temporary file and rename at every beat. Beside a page, a `.<name>.<n>.tmp` exists for the moment
  of the write. A reconcile racing it could commit one, as it always could beside a code file.
- **Every write walks its directories.** Each step takes a stat, a listing of its parent and a real
  path. All figures are from this host (APFS, JDK 25).
  - Before the listing, over 2,000 writes of a 4 KB page: a replace took 0.22–0.24 ms against 0.04 ms
    for the plain write it replaced, and an append 0.07–0.08 ms against 0.03 ms.
  - With 700 sibling directories in `problems/`, the median of nine rounds: a replace took 234 µs
    before the listing and 617 µs after. A boot's 110 replaces (25 pages, the index and 84 tag notes)
    took 26.3 ms, then 36.4 ms.
  - Reading a 700-entry directory alone took 0.36–0.53 ms. A history of 700 problems would add about
    0.3 s to each boot's page refresh. That figure is computed, not measured.
  - Nothing is cached. A cached listing would be stale exactly when a pull renames a directory, and
    a modification time does not reliably say so on a filesystem that keeps whole seconds, as HFS+
    does.
- **A folded alias is refused for writes everywhere**: by the listing in the tracker's image, and by
  either check on the host and on Windows. Reads differ. `ProblemFiles` compares real paths, which the
  image echoes, so in the image it reads through an alias that a write refuses. Such an alias still
  lies inside the repository, and this is #354's bound, unchanged here.
- **A root configured as `<link>/..` is written where it physically leads**, as git and the lock see
  it, while `ProblemFiles` reads the lexical path (#354's accepted cost). The first draft normalized
  the target and the root lexically. Dropping that in the review round, to see the `..`, also put
  such a root's writes back where they landed before #361.
- **Two reads still follow a link** (security S5, quality Q7; filed). `JsonlRecordStore.read()` reads
  a linked log's target as the history. Lines of that file that parse as records reach MCP and the
  attempt counter. No such line is pushed: the appends are refused, and git commits the link as a
  link. `RunLog`'s idempotency check reads a boolean from a linked run log. Both are readers, outside
  #354's `problems/` bound and this issue's scope.
- **A replace over a file another process holds open fails on Windows** (Q4; filed). Windows refuses
  to rename over an open file unless it was opened to share deletion, so a page an editor holds
  open is not rewritten. The failure is an I/O error, which a skipping writer does not skip.
- **A temporary file left by a crash stays beside its target** (Q5; filed). The next reconcile
  commits it. Code files always had this.
- **`RecordWrites` repeats parts of `StateDirectory` and `AtomicStateFile`** (Q6; filed): the
  per-directory checks, the rename, and a warn-once set each. Only the directory listing is shared,
  since the review round. Merging the rest is a refactor of its own.
- **`WatchToken` writes its token with `writeText`, which follows a link** (S4; filed). It lives in
  the tool's own `.ps/`, outside the records root and this issue's scope.
- **A bind mount under the root is a directory to every check.** Making one needs root on this
  machine. A junction is refused by the real-path comparison, untested on Windows.
- **Built bare, `FileRawSessionLog` copies unbounded.** That is the bare constructor, which tests
  use with destinations of their own. The composition root builds it `under` the records root, as it
  does for the guard.
- **Windows CI runs none of the link tests.** They skip through `canPlantLinksIn`, so on Windows only
  the normal-file paths of the walk run.

## Outcome

#361 on `fix/361-writers-never-follow-links`:

- `ff2462c` the bound, `RecordWrites`, with its tests;
- `2635d01` the boot pass goes on past a record it could not attach;
- `a66529a` every writer under `problems/`;
- `6532d1b` the writers at the root's level;
- three pins that review of the mutants asked for: `89e3005` the heartbeat's non-following read,
  `9597093` a failure that is no refusal still reaching a writer that skips refusals, and `58436dc`
  a root-level write outside the repository refused for the true reason;
- this page, `SECURITY.md`, the index, #354's pointer and progress, in the commit after them.

**Mutation.** 37 mutants were run against the branch, each to its own tests, and every file was
restored after.

The bound, 21 mutants, with the number of `RecordWritesTest` tests each failed:

| Mutant | Tests failed |
|---|---|
| the walk following a link | 4 |
| no real-path comparison per directory | 1, the case-fold test, which runs only where the filesystem folds case, as on this host |
| no bound check | 3 |
| no climb-out check | 1, after `58436dc`; it survived before |
| no regular-file check before an append | 3 |
| no directory check at the file | 1 |
| a replace written in place | 4 |
| a new file always owner-only | 1 |
| a replaced file's mode not kept | 1 |
| `ownerOnly` ignored | 1 |
| a refusal not said | 4 |
| a refusal said every time | 2 |
| no healing of a torn line | 1 |
| a delete without the walk | 1 |
| `createNew` writing over a file | 2 |
| `writeOnce` following a link | 1 |
| a replaced link not said | 2 |
| a link never named as one | 6 |
| the root not resolved | 23 |
| `replaceOrSkip` swallowing every failure | 1 |
| the append opened following a link | survives: it matters only inside the race above |

The writers, each routed back to the raw `Files` calls it made before (`CodeArtifacts` to
`AtomicStateFile`), with the tests each failed:

| Writer | Tests failed |
|---|---|
| `ProblemReadme` | 3 |
| `ProblemIndex` | 3 |
| `TagNotes` | 3 |
| `CodeArtifacts` | 3 |
| `RunLog` | 5 |
| `JsonlRecordStore` | 4, and 1 in `RecordWriterTest` |
| `FileExampleStore` | 4 |
| `writeStatement` | 3 |
| the runner | 3 |
| the sweep | 1 |
| the raw copy | 1 |
| the seeds without their link check | 2 |
| the seeds with neither the link check nor the bounded write | 2 |
| the heartbeat | 3 |
| the boot pass | 1 |
| the seeds' raw write behind their link check | survives: race only |

**Gates**, before the review round, at `58436dc` with this page, all exit 0:

- `./scripts/check.sh`;
- `./scripts/test.sh`: 2,117 JUnit tests, 73 new, 0 failures, 9 skipped (as before: 8 C# and the
  `icase` test), and node 4 of 4;
- `./scripts/build.sh`;
- `./gradlew verifyBranchCoverage`: `adapter/store` 84% (599 of 712), `application` 88% (340 of 383),
  every package at or above its floor;
- `./scripts/guards.sh`.

`RecordWrites` leaves 14 of its 84 branches uncovered. Each is a race, a Windows-only path or
unreachable on POSIX: a filesystem root handed as a target, a directory vanishing mid-walk,
`relativize` across drive roots, the `ATOMIC_MOVE` fallback, the temporary file's cleanup after a
failed move, and the branch for a filesystem with no POSIX modes. CI has not run this branch.

**The review round, at `545d6aa`.** A security review and a quality review attacked the branch.
Neither blocked the merge. Four findings were fixed here. Each fix has a test that was red against the
code before it, and the tests that only pin what held are named:

- **Folded aliases** (security S1, quality Q1 and Q2). The walk compared real paths with
  `Path.equals`, and three places got past it:
  - In the tracker's image the real path echoes the name asked for. The security review made
    `Problems/`, `PROBLEMS/` and `problemſ/` by hand and measured `wrote into alias: true`.
  - On Windows, `Path.equals` ignores case. The quality review predicted that the folding test fails
    on windows-latest.
  - On HFS+, the quality review measured, on a disk image, that the first write into a new
    Korean-titled directory was refused: NFD on disk against the NFC walked.

  `574bcb6` adds the listing check and compares both answers as text after NFC. Its tests play the
  image, HFS+ and a real path that leads elsewhere through `DiskAnswers`, on any platform. The
  image's alias was written and HFS+'s first write refused before the change. The real path that
  leads elsewhere pins what the old check already refused. The fix itself has not run in the image,
  on Windows or on HFS+.
- **The root-level bound** (S3). `underRoot` wrote `log/../.ps/git-credentials` and
  `.git/hooks/pre-commit` (measured). `748da22` refuses a `.` or `..` below the root, gives each
  root-level writer an allow-list of first names, and walks from the root as configured. Both paths
  were written before the change. So would a `.` or `..` that stays inside `problems/`, now refused
  too. A root configured through `..` pins what held.
- **A hard link** (S2). An append wrote `IMPORTANT | {"run":1}` into an outside file hard-linked as
  `runs.jsonl`. `629f5cc` refuses a file whose `unix:nlink` exceeds 1, except on Windows, where the
  view does not exist and the test skips. The append went through before the change.
- **The boot pass's catch** (Q3). Every `Exception` was logged by its class alone, a programming
  error without its stack. Since `99c7077`, an I/O failure is still logged that way. Any other fault
  is an ERROR with its stack, and cancellation is rethrown.
  - The fault test logged no ERROR before the change. The I/O and cancellation tests pin what held.
  - The fetch is wrapped (`fetched`), so no fetch detail can reach the stack trace.
- `aa0643c` pins that a problem writer handed the name `problems` itself writes nothing. It was
  added when the mutants were planned, since the mutant that drops that condition would have
  survived.

Filed by the coordinator as follow-ups, and listed under accepted costs: Q4, Q5, Q6, S4, and S5 with
Q7.

**Mutation, the review round.** 16 mutants of the new behaviours and of two earlier ones on
rewritten lines. Each ran against its own tests, every file was restored after, and all were killed.

| Mutant | Tests failed |
|---|---|
| no listing check | 1, the image |
| the listing without its NFC fallback | 1, HFS+ |
| the real path compared as text without NFC | 1, HFS+ |
| the real path compared with `Path.equals`, as before | 1, HFS+ |
| no real-path check | 1, the real path that leads elsewhere |
| no dot check | 2 |
| the target normalized before it is judged, as before | 2 |
| no allow-list at the root | 1 |
| a problem writer admitting `problems` itself | 1, the pin `aa0643c` |
| no hard-link check | 1 |
| an I/O failure logged as a fault | 1 |
| a fault logged by its class alone, as before | 1 |
| cancellation taken for a failure | 1 |
| the fault logged without its stack | 1 |
| the walk following a link | 4 |
| the root not resolved | 26 |

**Gates after the review round**, at `aa0643c` with this page, all exit 0:

- `./scripts/check.sh`;
- `./scripts/test.sh`: 2,128 JUnit tests, 11 of them new this round, 0 failures, and 9 skipped as
  before; node 4 of 4;
- `./scripts/build.sh`;
- `./gradlew verifyBranchCoverage`: `adapter/store` 84% (615 of 726), `application` 88% (340 of 383),
  every package at or above its floor;
- `./scripts/guards.sh`.

`RecordWrites` now leaves 12 of its 98 branches uncovered. Each is a race, a Windows-only path, or a
defensive case:

- a target that is the root itself;
- a directory vanishing mid-walk or before a delete;
- a directory that cannot be made for another reason;
- a replaced link said a second time;
- the temporary file's cleanup after a failed move;
- the `ATOMIC_MOVE` fallback;
- a filesystem with no POSIX modes, or no `unix` view.

CI has not run the branch.

**Not verified live.** The bound changes nothing a normal records repository can see. A rebuilt
server should write byte-identical pages and code files with the same modes. `git status` in the
records repository should stay clean after a boot with nothing to recover. A normal boot should log
no `Not writing` or `Replacing` line.
