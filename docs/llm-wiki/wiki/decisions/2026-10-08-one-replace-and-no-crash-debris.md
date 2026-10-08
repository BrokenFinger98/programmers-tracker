---
type: decision
project: programmers-tracker
tags: [storage, links, git, windows, refactor]
author: BrokenFinger98
created: 2026-10-08
updated: 2026-10-08
sources: [raw/sessions/2026-10-07-the-readers-that-followed-links.md]
---

# One replace for records and state, and no crash debris in a commit

## Context

#361 made every writer in the records repository refuse to follow a link
([[decisions/2026-10-08-no-writer-follows-a-link]]). It left three accepted costs, filed as follow-ups
and gathered in #386:

- **Q6, the copies.** `RecordWrites.replaceAt` and `AtomicStateFile.write` were one write kept twice: a
  temporary file beside the target, a mode, a move over it, the temporary file taken away on failure.
  `StateDirectory` made `.ps` with checks of its own that the records' walk also makes. Four classes kept
  their own "said once" set.
- **Q5, the debris.** A process killed between the write and the move leaves the temporary file beside
  its target, and the next reconciliation stages everything but `.ps`, so it committed the file: a copy
  of a committed file, as junk in the history. Code files always had this. Since #361 every page, tag
  note and the heartbeat's marker do too, and a reconcile racing a live write can see one.
- **Q4, Windows.** A replace over a file another process holds open was predicted to fail on Windows,
  and nothing had measured it.

The constraints:

- no behaviour change in the unifying, the tests of both callers green throughout;
- the primitive in `adapter/store`, with development-rules §1 kept true;
- a directory handle (#374, `SecureDirectoryStream`) must be able to replace the path walk later
  without changing the writers;
- a git change only to the commit pathspec constants of `CommandLineGitSync`, whose push path #373 and
  #375 are changing on other branches.

### The inventory

Before anything was unified, every behaviour of the three copies that an observer could see was listed,
and each was checked for a test that said it.

Pinned before #386:

- `AtomicStateFile`: a write skipped while the guard refuses `.ps`, said once per reason; written when git
  cannot say; its directory made; replaced whole, with nothing left beside it; `update` keeps the old
  document when the transform throws; owner-only, even after someone widened it; a kept mode when asked;
  a link replaced without its own mode.
- `StateDirectory`: `.ps` and a writer's directories made; nothing made below a link.
- `RecordWrites.replaceAt`: a directory at the target refused; a link replaced and said; the modes;
  nothing left beside.

Pinned by #386 (`a92652d`), each passing against the code as it was:

- `AtomicStateFile`: a link and a dangling link at the document replaced, and what they named kept or
  never made; a hard link broken, its second name keeping what it held; an empty directory replaced, and
  one holding something failing as the filesystem fails it (`FileSystemException`), with nothing left
  beside it; UTF-8; the exact words of a refusal.
- `RecordWrites`: a hard link broken; a FIFO replaced and said as "not a regular file"; a link said once
  per path across writes; UTF-8; directories with a plain `mkdir`'s mode; the exact words of a refusal
  and of a replaced link.
- `StateDirectory`: a writer's path under a records path that is a file is not inspected, transiently;
  its directories get `mkdir`'s mode.
- `RecordReads`, `FileRawSessionLog`: the exact words they say once — the raw log's not-listed words being
  #377's, whose work list main holds.

Not pinnable as such:

- **ownership.** A replaced file is a new file, owned by the server's user; the hard-link pins show it is
  a new file.
- **a directory made by another writer between the look and the make.** Race-only, and pinned directly
  on the shared function after its mutant survived (`b1415ed`).

A third copy, out of scope: `SessionProvider.writeAtomically`, in `protocol`, writes the tool's own
`.ps/session` the same way, through a `.session-<n>.tmp` beside it. `protocol` imports no adapter
(development-rules §1), and that file lies beside the checkout, outside the records repository, where no
reconcile reaches.

Changed on purpose, and only here: the temporary file's name. A state file's was `<name><n>.tmp` and a
record file's `.<name>.<n>.tmp`; both are now `.<name>.<n>.programmers-tracker.tmp`. It exists only
mid-write, or after a crash. It is longer, an ending of 24 bytes where it was 4, so a target whose name is
past 209 bytes now makes a temporary name past the 255 a filesystem allows, and the replace fails with
`File name too long` where, up to about 230, it used to pass. The tracker's own names stay far below: the
longest is a tag note's, `<slug>.md`, its slug cut at 60 UTF-16 units (`RecordLayout.MAX_SLUG`), so at most
183 bytes even in Hangul or CJK, and 29 for the longest tag in the shipped catalog.

## Options considered

The write:

1. **`AtomicStateFile` delegating to `RecordWrites`.** No new class, but `RecordWrites` writes only under
   the records root, through its walk, while `AtomicStateFile` also writes the tool's own `/watch` token
   beside the checkout. The record writer would have grown a mode that walks nothing.
2. **One class that is only the write, used by both** — chosen. Each caller keeps what is its own: the
   walk, the refusals, the link said once, the guard.

`.ps`'s checks: `StateDirectory` made the same three checks of `.ps` that the walk makes of every
directory a record write passes — a real directory, listed by its parent under exactly its name, resolving
to the path walked. It now takes the walk's own step through a new `RecordBound.made()`. The per-frame
check of a writer's directories below `.ps` stays a link check, as #360 decided; the walk's stricter step
there would refuse more.

The debris:

- (a) **A pathspec exclusion under a distinctive name** — chosen.
- (b) **A sweep at boot, before the startup reconciliation.** It cannot come before every commit: the
  composition root starts the lock and the heartbeat while beans are made, the web server and the
  scheduler when the context refreshes, and the startup runner only after. A live pass
  (`RecordWriter.attached`, then `git.reconcile`) and the scheduled backup both commit, and both can run
  before the runner. Nor can a sweep reach a temporary file in flight, which a reconcile racing a live
  write sees.
- (c) **An ignore rule in the records repository's `.gitignore`** — rejected at first, and added since the
  review, beside (a) rather than instead of it. It keeps the debris out of the owner's own `git add -A`
  too, an Obsidian Git backup among them. It cannot be the guarantee: the file is the owner's, who can drop
  the rule, though `RecordRepositoryIgnores` adds it back at the next boot. The first reason given against
  it was wrong: a rule the store seeds is no git change, which is all #386 restricted, and the reviewer
  measured no conflict with the pathspec.

What a regenerated page does if the Windows move fails as predicted:

- **fail that write, as any I/O failure does, and let its caller carry on as it does for one** — chosen.
  Not every caller carries on past the one page: the boot's vault refresh has no guard per page
  (`CodeAttachment.refreshProblemPages`), so a page held open stops that refresh there — the problem pages
  after it, the index and the tag notes wait for the next refresh, and the boot says so
  (`StartupReconciliation`). An attachment stops its own later writes the same way. A guard per page is a
  candidate, not done here: it changes what the boot does on every failure, for one not yet measured;
- **fall back to writing in place** — rejected: it writes through a hard link, lets a reader see half a
  page, and undoes what #361 made of a replace;
- **turn the failure into a refusal in `replaceOrSkip`** — not now. A refusal says "fix your repository",
  and a file held open is a moment, not a structure.

## Decision

**`adapter/store/FileReplacement`** is the write, once: a temporary file made beside the target in the
target's directory, written as UTF-8, given its mode, moved over the target with `ATOMIC_MOVE` and, where a
filesystem cannot, `REPLACE_EXISTING`; taken away if anything fails once it exists. **`FileMode`** is the
three modes its callers use:

- `KEPT_ELSE_PLAIN`, a record file: a regular file's own mode kept, a new one a plain write's, which the
  umask narrows;
- `OWNER_ONLY`, code files and the server's state, whatever someone widened the old file to;
- `KEPT_ELSE_OWNER_ONLY`, a document someone else made, the owner's `.gitignore`.

The mode is set when the temporary file is made, so an owner-only file is never wider, even for a moment;
a link's own bits are never kept. A clean-up that fails too is kept on the failure, never thrown in its
place. `RecordWrites` and `AtomicStateFile` hand their replaces to it, and the five operations a replace
makes on the target's directory — making the temporary file, writing it, setting its mode, the move and
the clean-up — are in that one class. The writers' other operations there are still by path: an append, a
delete, `AtomicStateFile`'s `createDirectories`, and the look `RecordWrites` takes at the target before a
replace.

**`SaidOnce`** is the "say each key once per instance" the four classes kept; each keeps its own keys and
words. **`StateDirectory`** verifies `.ps` with `RecordBound.made()`, and **`createdOrThere`** is the one
way the records and `.ps` make a directory.

**The temporary file is named `.<name>.<n>.programmers-tracker.tmp`** (`FileReplacement.TEMP_SUFFIX`),
and `CommandLineGitSync.RECONCILE_SCOPE` leaves out every hidden file with that ending, as it leaves out
`.ps`: `:(exclude,glob)**/.*.programmers-tracker.tmp`. The status check, the staging, the token search and
the commit all take that scope. Development-rules §1 lists the constant on the `git → store` edge. Since
the review, `RecordRepositoryIgnores` also seeds `.*.programmers-tracker.tmp` beside
`.programmers-tracker.lock`, for every `git add` that is not the tracker's.

**On Windows**, a test enabled there alone opens a page with `FileInputStream`, which shares reading and
writing but not deletion, as a sync client or a scanner may, and replaces it through `replaceOrSkip`. It
asserts the JDK's prediction: the failure is an `IOException` and not a refusal, the page keeps its old
bytes, and nothing is left beside it. If that holds, the posture stays: the write fails, its caller says it,
and the page is rewritten at the next refresh — in the boot's vault refresh, with the pages after it.

## Rationale

**One write, so the copies cannot drift.** The two had already drifted: a record file's temporary file
was hidden and a state file's was not. A fix to one, such as #374's handle or the debris's name, would
have had to be made twice.

**A directory handle replaces the walk, not the writers.** Every operation a replace makes on the target's
directory is in `FileReplacement`, and every check of a directory on the way is in `RecordBound`. #374
changes those two; `RecordWrites` and `AtomicStateFile` keep their signatures, and no writer of a page, a
note or a state document changes. An append, a delete and the writers' own looks stay by path until they
are moved there too.

**Why a store name may decide a git exclusion.** The rule is "adapters depend on each other only for a
primitive the other owns" (development-rules §1). What a temporary file is called is the store's to say,
and git needs to know it to leave it out, as it needs `.ps`'s name, which it already takes from
`StateDirectory.NAME`. One constant, one edge already there; the alternative is a second spelling of the
name in `adapter/git`, which drifts the day either changes. The name has to be the tracker's own, or a file
of the owner's would be left out with the debris; `.programmers-tracker.tmp` is pinned against an owner's
`.draft.tmp` and `scratch.tmp`, which are still committed.

**A wildcard first.** An exclusion that names an ignored path literally makes `git add --all` exit 1 — git
takes it as asking for that path — measured on git 2.48.1 with `*.tmp` ignored: a literal exclusion of the
debris file exited 1, the `**/` glob exited 0. A test pins that a `.gitignore` with `*.tmp` does not stop
the reconcile.

## Accepted costs

- **The debris stays on disk.** Hidden, never committed by the tracker, and ignored by the rule the server
  seeds, so the owner's own `git add -A` leaves it out too — unless the owner removed the rule, until the
  next boot adds it back.
- **The exclusion is the tracker's only.** A tool that stages the records repository with its own pathspec
  and `--force`, or ignores `.gitignore`, does not know the name.
- **Temporary files an earlier build left are committed once.** A crash under a build before #386 left
  `.<name>.<n>.tmp` beside a page, a note or a code file, or `.gitignore<n>.tmp` at the root (the state
  file's old name; the others of its kind sat under `.ps`, which no reconcile takes). Neither matches the new
  pattern or the seeded rule, so the first reconcile after the upgrade commits any such file a crash left,
  as every reconcile before it would have. A crash under this build leaves only the new name.
- **Two mutants survive.** Owner-only left to the JDK's default at creation is the same `rw-------` on the
  default filesystem, but the specification promises only that such a file "may" be narrower, so the mode
  is asked for. And a plain `REPLACE_EXISTING` without `ATOMIC_MOVE` deletes the target before the rename:
  only a reader racing the write could see the gap.
- **Windows is predicted, not measured.** The test runs on windows-latest only, and this branch has not
  been pushed.
- **A failed regenerated page waits for the next refresh.** On Windows, a page held open is not rewritten
  until it is closed and something rewrites it. In the boot's vault refresh, the problem pages after it,
  the index and the tag notes wait with it.
- **Any failed atomic move falls back to a plain one** — a candidate the review named, filed as a
  follow-up. The fallback to `REPLACE_EXISTING` was meant for a filesystem that cannot move atomically,
  but it runs after any failure of `ATOMIC_MOVE`, and then deletes the target before the rename.

## Outcome

#386 on `refactor/386-one-write-primitive`, rebased onto main at `9b4dcc1`, which holds #387 squashed as
`40bc5f5`, #377 before it, and #376 and #378's store half after it:

- `a92652d` the pins of the inventory;
- `ce60e12` `SaidOnce` for the four classes;
- `5fa6c7a` `.ps` through `RecordBound.made()`, and one `createdOrThere`;
- `a38fc20` `FileReplacement` and `FileMode`;
- `331c051` the debris's name and its exclusion from every reconcile;
- `8934dc5` the Windows pin;
- `b1415ed` the pin of a directory made meanwhile;
- this page, the note in #361's page, the index and progress, in the commit after them.

The branch first took #387's review fixes and main by two merges; the rebase replaced both. The one
change a merge carried beyond its conflicts — the pin of the raw log's not-listed words, held in #377's
words since main took #377's work list — is in `a92652d`, where the pin is made.

**Red first.** `SaidOnceTest`, `RecordBoundTest`'s `made()` cases and `FileReplacementTest` did not compile
without their code. Against the old reconcile scope, files left by a replace beside a page, a tag note and
a seed were committed, and a tree holding only one was committed as a change. Every pin of `a92652d`, and
every test of both callers, passed unchanged across the refactor.

**Mutation.** 21 mutants, each run against its tests and the file restored after. 19 were killed, one of
them only after a pin; two survive. A count is of the test classes its run read, so a lower bound; both
survivors were run against every adapter and application test.

| Mutant | Tests failed |
|---|---|
| a reason said every time | 11 |
| one said-once set for every instance | 13 |
| `made()` making nothing | 16 |
| the bound's answer escaping `StateDirectory` | 7 |
| the temporary file not beside its target | 1 |
| its name without the leading dot | 3, the git debris tests among them |
| a plain `.tmp` ending | 1 |
| no exclusion in the reconcile scope | 2 |
| owner-only made plain at creation | 8 |
| made plain, narrowed before the move | 3 |
| owner-only left to the JDK's default | survives: the same mode on the default filesystem |
| no mode ever kept | 3 |
| a mode kept for owner-only too | 4 |
| a link's own bits kept | 2 |
| no clean-up after a failure | 2 |
| no fallback after `ATOMIC_MOVE` | 2 |
| `REPLACE_EXISTING` alone | survives: race-only |
| ISO-8859-1 for UTF-8 | 3 |
| a record writer's owner-only ignored | 2 |
| a state file's kept mode ignored | 2 |
| a directory made meanwhile taken for a failure | 1, the pin; it survived before |

**Windows.** Not run: the test asserts the prediction above, and its first run on windows-latest is the
measurement. A failure there means the prediction was wrong, and is the result to record here.

**Gates**, all exit 0 at `b1415ed`, with this page:

- `./scripts/check.sh`;
- `./scripts/test.sh`: 2,545 JUnit tests in 174 classes, 0 failures, 12 skipped — the 11 as before and
  the Windows pin off Windows; node 4 of 4;
- `./scripts/build.sh`;
- `./gradlew verifyBranchCoverage`: `adapter/store` 87% (702 of 806), `adapter/git` 88% (443 of 498),
  `adapter/web` 82% (96 of 116), `application` 89% (395 of 439), every package at or above its floor;
- `./scripts/guards.sh`, 12 of 12.

**Not verified live**, and CI has not run the branch. A rebuilt server should write byte-identical files
with the same modes, and `git status` in the records repository should show no
`.programmers-tracker.tmp` after a normal boot.

### The review

The review of #386 found nothing blocking. Its findings, and what the branch did with each, on top of
`7b1593f`:

- **N1, the longer name.** Said under the inventory: a target name past 209 bytes now fails where, up to
  about 230, it used to pass; the tracker's own names stop at 183.
- **N2, five mutants that survived.** Each is pinned in `8894d00`, and each pin failed its mutant alone:
  `toAbsolutePath()` removed, tried on a zip file system so no test writes into the working directory
  (`createTempFile` was handed no directory and threw); a replaced link and each writer's and reader's
  refusals said under one key; and the reconcile's exclusion widened to visible names, against an owner's
  `notes/report.programmers-tracker.tmp`, which is committed.
- **N3, an earlier build's debris.** An accepted cost above, and #361's page qualified to match.
- **N4, the ignore rule.** `f7409bb` seeds `.*.programmers-tracker.tmp`. Red: real git showed the debris
  as untracked. A rule widened to visible names fails the same test, and a reconcile with the seeded rule
  in place still goes through.
- **N5, the wording.** The Windows posture says which callers stop at a failed page; the Decision and the
  Rationale scope the one class to the five operations of a replace.
- **N6.** `646a4a8`: `RecordBound`'s "never `.git` or `.ps`" holds for a record writer or reader, since
  `StateDirectory` asks for a bound of `.ps` alone.
- **N7.** The fallback after any failed atomic move, a candidate in the accepted costs; the coordinator
  files it.
- **N8.** `SessionProvider.writeAtomically`, a third copy out of scope, in the inventory.
- **N9.** `66f104c`: a clean-up that fails too is kept on the failure as suppressed. Pinned through
  `FileReplacement` itself in an append-only directory (`chflags uappnd`, macOS), which lets the
  temporary file be made and refuses both the move and its removal; red, the thrown exception carried
  none. `f8c1464`: `SaidOnce`'s lambda is `words`, no longer shadowing `say`. `FileMode` keeps its name:
  #374 builds on it.

**Gates after the review**, all exit 0 at `646a4a8`, with this page:

- `./scripts/check.sh`;
- `./scripts/test.sh`: 2,553 JUnit tests in 174 classes, 8 of them the review's, 0 failures, 12 skipped as
  before; node 4 of 4;
- `./scripts/build.sh`;
- `./gradlew verifyBranchCoverage`: `adapter/store` 87% (705 of 808), `adapter/git` 88% (443 of 498),
  `adapter/web` 82% (96 of 116), `application` 89% (395 of 439), every package at or above its floor;
- `./scripts/guards.sh`, 12 of 12.

The append-only pin runs on macOS alone and the held-open one on Windows alone; neither has run in CI.
