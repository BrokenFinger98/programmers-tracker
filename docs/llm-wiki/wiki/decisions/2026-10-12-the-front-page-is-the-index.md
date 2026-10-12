---
type: decision
project: programmers-tracker
tags: [vault, seeds, index, ownership, records, constitution]
author: BrokenFinger98
created: 2026-10-12
updated: 2026-10-12
sources: [decisions/2026-08-12-the-server-counts-and-names-nothing, decisions/2026-08-11-korean-for-the-user-facing-half, decisions/2026-08-14-the-seed-ships-in-the-form-its-reader-rewrites-it-to, decisions/2026-10-08-no-writer-follows-a-link, raw/sessions/2026-10-12-the-front-page-the-hook-and-two-stale-notes.md]
---

# The front page is the index, and the guide moves to `GUIDE.md`

## Context

The records repository is pushed to GitHub and may be public. Its root `README.md` was a seed: the
usage guide, written if absent and refreshed only while untouched under the seed ledger (#300). So
github.com opened every records repository on instructions for using it.

#292 put an index of what has been solved one directory down, at `problems/README.md`, regenerated
from the records at every attachment and boot. It rejected the root for two reasons
(`.harness/state/progress.md`, 2026-08-14):

- the root README becoming the index "demotes a page carrying the graph groups, the `.ps/` warning
  and the timezone note, and moves a Korean twin and its `translated-from` hash with it";
- markers in the root README (`<!-- index:start -->`) "make one file *half* server-owned".

**The owner decided on 2026-10-12 that the index goes on the front page (#414).** This page records
how: what the front page says, whose it is, and how a repository that holds the guide there gets the
index without losing an edit.

## Decisions

### 1. The guide moves to `GUIDE.md` and `GUIDE.ko.md`, and stays a seed

The same content, under the same ledger rule. The front page's header links both. This answers the
first half of #292's first reason: nothing the guide carries is lost, and it is one click from the
front page. Its second half is accepted: the Korean twin moves, which renames one entry in the
constitution's list of six twins. (The markers objection is answered in decision 3.) **That is a constitution change, made by the owner's decision of 2026-10-12.** It renames an
entry and does not add a seventh. `scripts/guards.sh` needed no change: its twin check walks every
tracked `*.ko.md`, so `GUIDE.ko.md` is checked against `GUIDE.md`'s blob as `README.ko.md` was
against `README.md`'s.

### 2. `problems/README.md` stays, unchanged

Two options: keep it as it is, with the same render and links relative to `problems/`, or delete it
now that the root has the index. **It stays.**

- **The smaller change.** `ProblemIndex`'s output and its 15 tests are untouched, and nothing is
  deleted from anybody's repository. Deleting it would need a delete path, and a rule that brings it
  back the moment the owner makes the front page theirs, since the index must then live there.
- **Nothing confusing.** One rule: `problems/README.md` is always the index. The alternative is a
  file whose existence depends on a decision about another file, which a reader cannot see. GitHub
  still renders it where somebody browses into `problems/`, which is why #292 put it there.
- **The cost:** two pages carry the same table, so each grading's commit changes both. They are
  rendered by one class, `IndexTable`, from the same records in the same call, and cannot disagree.

### 3. Whose the front page is: the ledger for the guide it was, its own seal for the index it becomes

Four things can stand at `README.md`. The server's are: nothing; the guide an older server seeded and
nobody touched; the index this server wrote. Everything else is the owner's, permanently: an edited
guide, a page of their own, a link, a guide seeded before the ledger existed.

**The guide it was is judged by the ledger, once, at boot.** `VaultDashboard` applies #300's test to
the two retired seeds. A file still holding the recorded bytes was never anybody's, so it is taken
away. `README.md` then holds nothing, and the index takes it in the same boot. `README.ko.md` gives
way to `GUIDE.ko.md`. One byte different, or no record at all, and the file is kept as it is. The
review added two conditions:

- **What replaces it must be there.** A retired seed goes only while its guide (`GUIDE.md` for
  `README.md`, `GUIDE.ko.md` for `README.ko.md`) is a regular file, judged without following a link.
- **The pair goes together.** The old `README.md` links `README.ko.md` by name under its title. So
  the twin stays only while the README's bytes hold its name, or the README cannot be read to say
  they do not. A README kept as the owner's that links it keeps it, or their page would link
  nothing. Gone (absent, or retired in the same pass), the index, or a front page the owner wrote
  without the link, and the twin goes: the first rule of the review, "only when gone or the index",
  kept the untouched Korean guide forever beside `GUIDE.ko.md` under a front page written from
  scratch, and nothing said so (the second review's finding 3). Only the twin edited still retires
  the README and keeps the twin.

**The index it becomes is never compared with the ledger.** The ledger's `README.md` entry names the
old guide, and no index will ever hash to it. Had the ledger stayed the comparison, the second boot
would have taken the server's own index for an owner's edit and stopped writing it. Two other ways of
remembering were considered:

- **Record every index write in the ledger.** Rejected:
  - The ledger lives in `.ps/`, which is never committed. A clone has no record, and neither has a
    machine whose `seeds.json` was lost or corrupted. No record means edited (#300), so the front page
    would stop moving there while still claiming to be the index: a stale copy of the records where a
    reader looks first.
  - The index changes at every grading. A ledger write is a state write, and every state write first
    asks git what it tracks under `.ps` (#360). That would add a git process to every grading, inside
    the writer's confined section.
  - The page and the ledger are two files. A crash between the two writes leaves them disagreeing,
    and the page is then misread in the same way.
- **Recognise the index by its shape** (the header, a count line, table rows). Rejected: an edit
  inside a cell keeps the shape, and would be overwritten. That is the reverse mistake.

**Chosen: the page carries its own seal** (`PageSeal`, in `RootIndex.kt`).

- Its first line is an HTML comment holding the SHA-256 of everything after it.
- The page is the server's only while its first line is a seal and the seal's digest is the SHA-256
  of the body after the first line break.
- A byte of the body changed breaks the seal, and the page is then the owner's. So does a line added
  above the seal, inside it (which splits it over two lines) or below it.
- **The framing is a format; the words are free** (the review's finding 1). A seal is one line,
  from `<!--` to ` -->`, ending in `sha256:` and exactly 64 lowercase hex just before the close.
  Every deployed page carries that framing, so changing it needs a reader for the old one beside it.
  The words between are presentation and are not checked: the first version compared the whole line
  with today's words, so a release that reworded them would have orphaned every deployed page. A
  page sealed under older words is the server's, and is rewritten under the new. A golden test pins
  the first line this version writes. Its words now say the rule: "Change what this page shows and
  it is yours", since a change to the comment's own words is overwritten.
- **One comment, read by hand, at most 1,024 characters** (the second review's findings 1 and 2).
  The round-1 regex backtracked on a hostile first line: the critic measured 173 ms at 112 KiB,
  15 s at 1 MiB and about 4 min at 4 MiB, on the boot path and at every grading, from a page a pull
  can deliver (the class of #387's FIFO). The line is now parsed left to right with no regex, and
  only up to 1,024 characters, about five times a real seal; a 1.4 MiB hostile line answers in
  milliseconds. And the regex took a line such as `<!-- … sha256:H --> **My plan** <!-- mine -->`
  for a seal, though github.com shows the text in the middle, so the next write wiped the owner's
  words. A close before the end, `-->` or the `--!>` a browser also closes on, now makes the page
  the owner's.
- It is the ledger's notion, "exactly the bytes this server wrote", carried in the file it vouches
  for. So it survives a clone, needs no state, and costs nothing per grading.
- It is written beside the page and moved over it with the page, as one file, through
  `RecordWrites`. No crash can leave a page and a seal that disagree.
- A seal copied onto another page vouches for nothing.
- Only line endings are set aside before the check. Git's `core.autocrlf` checks a page out with
  CRLF, which is the checkout's doing and not an edit. The server writes LF alone.

**Absent is the server's.** That is how a fresh repository gets the page. It is also how an owner who
deletes their own front page gets the index back, the same rule as for a deleted seed.

So `README.md` is compared against the ledger once, by `VaultDashboard`, while it may still be the
old guide. After that it is compared against its own seal, at every write. A regenerated index always
carries a valid seal, so it is never taken for an owner's edit. An owner's edit breaks the seal, so
it is never taken for a regenerated index.

**A front page that is the owner's is never written.** The index stays in `problems/README.md`, and
the server says so once for each run through `SaidOnce`, naming the file and where the index is,
never its content. A page that reads but carries no seal of its body is the owner's choice, so it
is stated at INFO, not warned, as a repository with no remote is: the first version warned, and the
review moved it (finding 8). Its message names the guide an older version seeded among what the
page can be (finding 5). What the read refuses (a link, a FIFO, a directory) is warned. A link at
`README.md` is the owner's, as at a seed (#361): left alone, never written through or over.

**This is neither marker #300 nor #292 rejected.** #300 rejected a marker inside a seed. A seed is a
document its reader is expected to make their own, and a marker would sit in their way. The index is
generated and rewritten whole. Its comment is invisible on github.com and in Obsidian's reading view,
and it is the one way a page can say whose it is wherever it is cloned. #292 rejected marker regions
that make one file half server-owned. This file is wholly the server's or wholly the owner's, never
both.

### 4. The header

```
<!-- Written by programmers-tracker. Change what this page shows and it is yours: … sha256:<64 hex> -->
# Programmers solving records

Written by [programmers-tracker](https://github.com/BrokenFinger98/programmers-tracker) after every grading. The guide: [English](GUIDE.md) · [한국어](GUIDE.ko.md).

25 problems recorded, 25 passed.

| Problem | Level | Kind | Language | Verdict | Submits | Last submit |
```

- **What the repository is, and where the guide is.** No other prose. It is in English, as all tool
  output is. The Korean guide's link is labelled in Korean. That is the endonym every language switch
  uses, and this repository's own README uses it too: a reader who reads only Korean must be able to
  find it.
- **The title names no directory.** A repository's name is configurable, and in the container the
  record directory is a mount point. So the title says what the records are, not what the old seed
  called the repository.
- **Counts and recorded facts, never a verdict**
  ([[decisions/2026-08-12-the-server-counts-and-names-nothing]]). Problem titles stay as recorded,
  and a blank title is linked by its lesson id, as in `problems/README.md`.
- **A fresh repository gets the page before its first record.** The counts read 0 and there is no
  table, since a table with no rows reads as a defect. #292 refused an index of an empty `problems/`
  because it helps nobody. A front page that says what the repository is helps its first visitor.
  #292's property still holds, restated: a boot that recovered nothing commits nothing, because the
  page is the same bytes at every boot. A fresh repository's first boot commits the page alone, beside
  the seeds.
- **An empty history leaves a page already there as it is** (the review's finding 4). A history read
  as empty, with the log moved away or every line unreadable, rewrote the front page to zero, where
  `problems/README.md` is left as it is. Both are left now; only a repository with no page gets the
  zero-count one.
- **Links through `RecordLayout`.** `problemNoteLinkFromRoot` resolves the directory a problem has,
  so a problem Programmers renamed is linked where it is (#233).

### 5. Every write goes through `RecordWrites`

- **The front page** is written by `RecordWrites.underRoot(layout, {README.md}).replaceOrSkip`:
  temp-and-move, no link followed, and a refusal or a failure skipped and said once (#361, #406,
  #407).
- **It is read** through `RecordReads.underRoot`, the same walk. A read refused there (a link, a
  FIFO, a directory) is the owner's file.
- **The retired seeds** are removed by `RecordWrites.removeFile`: a regular file only, and a link is
  left alone and said.

`removeFile` could not remove a file at the root's own level. It found the directory to walk as the
file's parent, which at the root is the root itself: no name below it, so the bound refused it as
outside the repository. The first green run caught this. It now walks to the file as a write does, so
the root's level and `problems/` take the same path. The review found that the walk resolved the
records root's real path first, so a root that is not there threw where it had answered "nothing
left there" (finding 6); the target's directory is looked for before the walk again.

## Accepted costs

- **Two copies of the index in every grading's commit.**
- **One comment line in the page's source**, whose digest changes at every rewrite: one more changed
  line per diff.
- **An owner who edits the front page in place keeps a frozen copy there.** The page is theirs, and
  the live index carries on in `problems/README.md`. The server states so at INFO at every start,
  and deleting the page hands it back.
- **Restoring the retired guide's exact bytes as `README.md`** (from history, say) counts as
  untouched. The next boot takes it away again. The ledger's entry is kept rather than forgotten,
  because forgetting it would be a state write to settle a case nobody has met.
- **The read and the replace are not one step.** An edit saved in the instant between them is
  overwritten. The seeds have the same window.
- **A guide seeded before the ledger existed is kept**, with the index only in `problems/README.md`.
  Absence is not permission (#300). The same holds wherever the ledger cannot vouch for the old
  guide (the review's finding 5, accepted): on a clone with no ledger, or in a CRLF checkout whose
  bytes the recorded hash does not match, the old guide is kept as the owner's, and the server
  states that once at each start. Removing `README.md` by hand gives the index. Matching the hashes
  of every guide ever shipped was not added (YAGNI).
- **Any file edited on github.com stops the pushes** (the review's finding 3; the second review
  widened it from the front page to every file, the owner's notes and guide included). The server
  never pulls or fetches. A commit made on the website puts the remote ahead, so every later push is
  rejected, logged as a failed push and left to the daily backup, and records stop reaching GitHub
  until the owner merges by hand. Measured on a clone that never fetched, as the tracker's is: git
  answers `! [rejected]        HEAD -> main (fetch first)`, with the hint "Updates were rejected
  because the remote contains work that you do not have locally". (The first round quoted "the tip
  of your current branch is behind", which is what a clone that had fetched is told.) On the front
  page the hand merge conflicts wherever the web edit meets the lines a grading rewrites, and the
  seal line changes at every grading: measured, the merge stopped with a content conflict in
  `README.md`. An edit at the bottom of a ten-row table, far from the rewritten lines, merged cleanly
  and left a page whose seal no longer matches, which is then the owner's. The guide says to edit
  any file in the clone the tracker writes to, never on github.com.
- **A `GUIDE.md` the owner already had is theirs.** The guide is not seeded over it, an untouched old
  README is still retired, and the front page's link leads to the owner's file. The old guide's text
  is then only in the repository's history.

## Outcome

Implemented in #414, in four code commits on `feat/414-root-index`:

- `a04aa83`: `removeFile` at the root's own level;
- `73cef31`: the guide moves;
- `ef0ceb7`: the index on the front page;
- `8e79f1d`: the retirement's notice pinned.

**Red first.** The tests were written against a `RootIndex` that wrote nothing, with the old seeds still
in place: 32 of 82 tests in the five classes failed, each because the feature was absent. The
`removeFile` refusal surfaced only in the first green run, as the warning "it lies outside the records
repository" in the retirement tests. Two `RecordWritesTest` cases were then written red against it,
before the fix.

**Mutation.** 24 mutants covered `RootIndex`, `PageSeal`, `IndexTable`, `VaultDashboard`,
`RecordWrites.removeFile`, `FileDerivedArtifacts` and `RecordLayout`. Each ran against the store,
application and config tests, 1,100 tests a run.

- 23 were killed at first.
- The survivor dropped the INFO line that says a retired seed was taken away. Nothing asserted on the
  owner's only notice of the move. A test now does, and kills it.
- ⚠️ (old) — "One planned mutant compared the seal's first line alone. It was equivalent to the
  original, so it was replaced by one that compares the digest alone. That one showed no test edited
  the mark's own words; a test case now does." **Reversed by the review** (finding 1): the words are
  free, so a page whose seal words differ is the server's, and that case became the
  forward-compatibility test.

**Measured on a copy of the owner's records** (`b1e116e`, with its remote removed, the push credential
emptied and no token given). The built jar was booted with `TRACKER_RECORD_REPO` pointing at the copy.

- **Boot 1** wrote `GUIDE.md` and `GUIDE.ko.md`. It took both untouched READMEs away, since the ledger
  held their hashes, and wrote the index. It logged no WARN or ERROR. Its reconcile commit, `6123161`,
  holds three entries and nothing else: `README.ko.md → GUIDE.ko.md` (90% similar), `GUIDE.md` added
  and `README.md` modified. `problems/README.md` and every problem page were unchanged.
- **The front page** has 25 rows, each the row `problems/README.md` has, linked from the root. All 27
  relative links resolve, and the seal matches its body.
- **Boot 2** made no commit and left a clean tree, with all 256 files outside `.git` and `.ps`
  byte-identical.
- **Boot 3** ran after a line was added to `README.md`. The file kept its bytes, the server warned
  once, and the index stayed in `problems/README.md`. Since the review that is said at INFO.

CI ran green on ubuntu, macos and windows at `b94053e`, before the review round. ⚠️ (old) — "Not yet
run in the owner's container." It ran there at 11:03 the same day: see "Merged and verified live" below.

### Review round 1 (2026-10-12, PR #415)

An independent critic found nothing blocking, and no way the owner's content is lost. Its ten
findings, each with a test shown failing first, in new commits:

| # | Finding | Answer |
|---|---|---|
| 1 | The seal's words were compared, so rewording them would orphan every deployed page | Framing checked, words free; golden and forward-compatibility tests (`e41ea86`) |
| 2 | The pair retired unevenly: an owner's README linked a twin that was gone | The twin goes only with the README gone or the index (`05a6a38`) |
| 3 | A front page edited on github.com stops the pushes | Measured; the guide says so, and an accepted cost above |
| 4 | An empty history zeroed the front page | Left as it is when a page is there (`7ed7dba`) |
| 5 | Retirement relies on the per-machine ledger | Accepted; the message names the old guide (`24d43ce`), a cost above |
| 6 | `removeFile` threw for a missing records root | True again, as before (`cb5f4cb`) |
| 7 | Retirement did not check what replaces it | Only while its guide is a regular file (`05a6a38`) |
| 8 | Log levels and wording | INFO for the owner's page, WARN for a refused read (`24d43ce`); "Could not retire" (`05a6a38`) |
| 9 | The 2026-08-13 ADR still said deleting the README resurrects it | Marked ⚠️ (old), linked here |
| 10 | Test gaps | A FIFO and a directory at `README.md` for both writers; the Korean twin's notice |

The critic's harness cases, rerun after the fixes:

- `pair` keeps the twin.
- `absent-root` answers true.
- `empty2` leaves the page at its count.
- `note` finds the page sealed under older words intact. Its own write is empty, so it leaves the
  page, as finding 4 decided.
- `pair-reverse` retires the README and keeps the edited twin, as before.
- `noledger` and `crlf-retire` keep the old guide, as accepted, and are stated at INFO.
- `bom` and `case` are unchanged. A BOM is a byte added above the seal, so the page is the owner's.
  `Readme.md` on a case-folding volume held the untouched seed's exact bytes, so it was retired.

### Review round 2 (2026-10-12, PR #415)

The critic re-reviewed `b94053e..8b3f4c9`. Every round-1 finding was closed or accepted, and nothing
blocked. The fixes had introduced four small issues, each answered in a new commit with its test
shown failing first:

| # | Finding | Answer |
|---|---|---|
| 1 | The seal's regex was quadratic on a hostile first line, on the boot path | Parsed by hand, at most 1,024 characters (`4437458`) |
| 2 | Owner text after a close on the seal's line counted as intact, and was wiped | One comment: `-->` or `--!>` before the end is the owner's; the note's words say so (`4437458`) |
| 3 | Every kept README held the twin back, even one that never names it | The twin stays only while the README's bytes name it (`1cf4411`) |
| 4 | Docs: the advice covered only pages the server writes; a stale WARN; a misquoted git message | Any file; INFO; git's own words for a clone that never fetches (the docs commit after them) |

The critic's new probes, rerun after the fixes:

- `Seal visible`: the page is not intact, the write leaves it, and the owner's text survives.
- `Seal redos`: first lines of 7 KiB, 112 KiB, 1 MiB and 4 MiB each answer in 0 to 4 ms. Before the
  fix, this machine took 181 ms at 112 KiB and 716 ms at 225 KiB, quadratic.
- `TwoBoots`: the twin is kept while the owner's README links it, and goes on the boot after that
  README is removed, beside a sealed index.

The round-1 cases `pair`, `pair-reverse`, `noledger`, `note`, `absent-root` and `empty2` answer as
before.

### Merged and verified live (2026-10-12)

CI ran green on all three systems after each round as well, seven checks at `8b3f4c9` and seven at
`8af6a30`. PR #415 was squash-merged as `4583f36` at 11:02. The owner's container was rebuilt from it
(raw/sessions/2026-10-12-the-front-page-the-hook-and-two-stale-notes.md). Measured on the owner's
records repository:

- **Boot 1 (11:03)** made one reconcile commit, `6ccde3e`: `README.ko.md → GUIDE.ko.md` (R086),
  `GUIDE.md` added and `README.md` modified. No other tracked file changed. The copy's rename was 90%
  similar; the guide gained its editing advice in between.
- **The ledger held both hashes.** Each README was said at INFO to be taken away "still exactly as this
  server seeded it". There was no WARN or ERROR line.
- **The front page.**
  - Its seal's digest, `badb8264…`, is the copy's. The note's words changed in round 2, and the digest
    did not: the body is the same records, and the words are free, as decision 3 says.
  - "25 problems recorded, 25 passed", in the same table as `problems/README.md`.
  - All 27 relative links resolve.
- **Boot 2 (11:04)** made no commit. `README.md` stayed byte-identical, and there was no WARN or ERROR.
- **Not on github.com yet.** The server never pulls, and the host did not push, so that the push goes
  through the server's token gate. `6ccde3e` waits for the next pass or the 23:00 backup.
