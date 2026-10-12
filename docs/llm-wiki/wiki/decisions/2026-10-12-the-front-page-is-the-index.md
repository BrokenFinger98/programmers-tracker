---
type: decision
project: programmers-tracker
tags: [vault, seeds, index, ownership, records, constitution]
author: BrokenFinger98
created: 2026-10-12
updated: 2026-10-12
sources: [decisions/2026-08-12-the-server-counts-and-names-nothing, decisions/2026-08-11-korean-for-the-user-facing-half, decisions/2026-08-14-the-seed-ships-in-the-form-its-reader-rewrites-it-to, decisions/2026-10-08-no-writer-follows-a-link]
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
way to `GUIDE.ko.md`. One byte different, or no record at all, and the file is kept as it is.

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
- The page is the server's only while it is exactly the seal of its own body, followed by that body.
- One byte changed anywhere breaks the seal, and the page is then the owner's. That covers the body,
  the comment, and a line added above, inside or below.
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
the server says so once for each run. It logs a `WARN` through `SaidOnce`, naming the file and where
the index is, never its content. A link at `README.md` is the owner's, as at a seed (#361): left
alone, never written through or over.

**This is neither marker #300 nor #292 rejected.** #300 rejected a marker inside a seed. A seed is a
document its reader is expected to make their own, and a marker would sit in their way. The index is
generated and rewritten whole. Its comment is invisible on github.com and in Obsidian's reading view,
and it is the one way a page can say whose it is wherever it is cloned. #292 rejected marker regions
that make one file half server-owned. This file is wholly the server's or wholly the owner's, never
both.

### 4. The header

```
<!-- Written by programmers-tracker. Edit this page and it is yours: … sha256:<64 hex> -->
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
the root's level and `problems/` take the same path.

## Accepted costs

- **Two copies of the index in every grading's commit.**
- **One comment line in the page's source**, whose digest changes at every rewrite: one more changed
  line per diff.
- **An owner who edits the front page in place keeps a frozen copy there.** The page is theirs, and
  the live index carries on in `problems/README.md`. The warning says so at every start, and
  deleting the page hands it back.
- **Restoring the retired guide's exact bytes as `README.md`** (from history, say) counts as
  untouched. The next boot takes it away again. The ledger's entry is kept rather than forgotten,
  because forgetting it would be a state write to settle a case nobody has met.
- **The read and the replace are not one step.** An edit saved in the instant between them is
  overwritten. The seeds have the same window.
- **A guide seeded before the ledger existed is kept**, with the index only in `problems/README.md`.
  Absence is not permission (#300).
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
- One planned mutant compared the seal's first line alone. It was equivalent to the original, so it
  was replaced by one that compares the digest alone. That one showed no test edited the mark's own
  words; a test case now does.

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
  once, and the index stayed in `problems/README.md`.

⚠️ Not yet run in the owner's container, and CI has not run the branch.
