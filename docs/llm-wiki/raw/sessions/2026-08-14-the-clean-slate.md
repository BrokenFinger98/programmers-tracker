# 2026-08-14 — the clean slate

Raw session record. Immutable (wiki schema §1). Recovered from `raw/inbox/` during the
2026-08-14 ingest: this segment was worked and merged on the day and **never became a session
page**, which a check before deleting the snapshot caught — `#300`, `#304`, `#306` and
`SeedLedger` appeared in no raw page.

Continues `2026-08-14-the-night-the-records-learned-the-question.md`, from the point where the
owner had read the vault in Obsidian and asked for the accumulated test data to be wiped so the
tool could be met as a first-time user meets it.

---

## The statement moves into the page, and an objection that had expired

The owner asked why `statement.md` was a link rather than the text itself. The honest answer is
that **my original objection had expired.** #275 chose an embed over inlining because the problem
README is regenerated on every grading, so an inlined statement would have to be re-fetched every
time to survive. That stopped being true the moment `statement.md` existed as a file — the text is
on disk, and inlining costs a read.

#293 had then replaced the embed with a markdown link, correctly, and the one thing it cost was
*one page*. Inlining gives it back in all three renderers rather than the one Obsidian gave it in.

`statement.md` stays: this is derived and that is the source. Delete the derived file and nothing
is lost; delete the source and the only way back is a request to Programmers. It is also the copy
a reader may annotate, which is the argument `notes.md` already rests on.

**Placed last, against what the issue first proposed.** A KAKAO statement runs several kilobytes,
so putting it first pushes every page's attempt history below the fold — and the history is the
half a *record* is opened for.

**A new cost, made visible by the owner's graph screenshot**: the link had made `statement` a node
with an edge, six of them on the map. Inlining removes the link and those become isolated dots.
Filterable (`-path:statement`), and the tag map is unaffected because it is built from
problem↔tag edges — but named rather than discovered.

**That same screenshot closed #293's open question.** Markdown links *do* draw edges in Obsidian:
README↔tag edges are on the map and the colour groups still fire. That was the one claim #293
shipped without being able to verify from outside Obsidian.

## #300 — the third state a seeded file can be in

`README.md`, `README.ko.md` and `dashboard.base` are seeded write-if-absent (#254), on a rule that
is right and did not change: *editing is respected forever; deletion is read as loss, not intent.*

Absent and edited were the only two states it considered. The third is **untouched, and stale** —
and twice on 2026-08-13 an improvement reached the shipped seed and the one existing vault only
because somebody edited it by hand: the graph's `kind` colour groups (#271) and the `Kind` column
(#274). Every future seed change has that shape and the count of hand-patches only grows.

`SeedLedger` records the SHA-256 of what the server wrote, in `.ps/seeds.json` beside the timers
and the backup marker (#126). A file that still hashes to that is ours to replace; one character
different and it is theirs, permanently.

**No record means edited.** A vault seeded before the ledger has none, and absence is not
permission — the mistake runs one way only. A corrupt ledger reads as empty for the same reason.

Rejected: shipping the bytes of every version ever seeded (unbounded growth, needs a release
process this project does not have), and a marker inside the file (visible in a document meant to
be read, deletable by accident, and it puts the tool's bookkeeping in the reader's face).

> The premise this rests on — *byte-identical means nobody touched it* — was falsified for
> `dashboard.base` the same week; see
> [[decisions/2026-08-14-the-seed-ships-in-the-form-its-reader-rewrites-it-to]].

## #272 follow-up — the runner exemption, 66% → 78%

`domain/calc/runner` was the largest untested surface in the repository. #285 had already
corrected *why*: the seven execution suites exist, run 49 tests on CI with none skipped, and move
the number by nothing — the generation branches are what is uncovered, and executing generated
code proves it correct without visiting a new path.

Two table-driven suites took it to 679/862. Table-driven because the shapes are a matrix, and a
language missing from the list shows up as a gap — the shape §6.2 already uses for the
compile-error fixtures, after #212 classified six of seven languages by patterns written for two
others.

**Both tables failed on first run because I asserted a refusal the code does not make.** Twice:

- a quoted number for a numeric parameter (`int` ← `"7"`) is **accepted**, rendered `7`;
- an unquoted number as stdin is **accepted** and fed as its own text — while the refusal message
  three lines away says "quoted text".

Neither is measured — no `examples.json` and no fixture has ever carried either shape — so the
code was not changed on a guess. Both are pinned as the behaviour that exists, labelled *never
measured*. Third and fourth time in two days that an assertion of mine turned out to be an
assumption; the difference is that this time it reached a test and nothing else.

## #298 — the one CI job that downloaded the world every run

Six of seven jobs cache `~/.gradle` through `setup-gradle`. The seventh could not: `docker image
boots` runs `docker build`, and the Gradle that matters runs **inside** the image, where the host
cache does not reach. Every run re-resolved Spring Boot, Ktor and the rest — from a layer the
Dockerfile already isolates for exactly this purpose and that nothing was caching.

It had already cost a **429 from Maven Central** on one run: a job that re-downloads everything is
one rate limit away from a red build that says nothing about the code.

Now built through `docker/build-push-action` with `type=gha` layer cache.

## #304 / #306 — the server ignored one editor's directory and committed the other's

`RecordRepositoryIgnores` carried `.obsidian/`, added after a 23:00 backup committed nothing but a
changed graph setting under a message about records (#234). `.idea/` was not there — and measured
on the owner's repository, it was **tracked and pushed**, sitting beside `problems/` and `log/` on
github.com.

The argument that added `.obsidian/` never mentioned Obsidian. It is that `git add --all` cannot
tell an editor's state from a record, and the server is the thing doing the committing. This is a
Kotlin project; a vault opened in IntelliJ is not an edge case.

**It does not untrack what is already committed.** An ignore rule has no effect on a tracked file,
and a server that started running `git rm --cached` on a user's repository would be an ignore rule
grown into something else.

Then the owner asked whether `.obsidian/` needed to be out of GitHub at all. It did not, and the
history said so — file by file, before the rule landed:

```
5 changes  .obsidian/workspace.json   which panes are open, scroll position, window size
5 changes  .obsidian/graph.json       the colour groups and filters
2 changes  core-plugins / appearance / app.json
```

`workspace.json` changes because Obsidian was **opened**. `graph.json` changes because somebody
**configured the graph** — work that took time and thought. #234's complaint was *noise*, and the
blanket rule answered it by throwing away the valuable half, in a tool whose whole argument is
that nothing is lost.

Narrowed to `workspace.json` and its mobile twin, so a vault cloned onto another machine opens
configured rather than blank. **And a rule the reader already wrote now wins**: `alreadyIgnores`
matched a line exactly, so a `.gitignore` saying `.obsidian` would have had
`.obsidian/workspace.json` appended underneath it — the server arguing with a broader decision
somebody had already made. It checks ancestors now.

> That narrowing could not reach any repository created before it, which became #308 — answered
> with a paragraph in `docs/bootstrap.md` rather than a migrator, because five ignore rules in the
> tool's history and one ever narrowed does not pay for machinery.

## The wipe

Everything the tool had accumulated while being built was removed from the record repository, so
the next boot would be a first boot. What survived: `tags/` (83 notes, all `untouched`), the three
seeds, `.obsidian/` (now versioned), `.gitignore`.

First boot after it logged `Added [.idea/]`, three `Wrote /records/…` seed lines, three hashes
recorded by `SeedLedger`, no orphan-frame warning, and `recorded=0`.

**Nothing had yet been solved into it**, which is the gap the next session's first-run test closed.
