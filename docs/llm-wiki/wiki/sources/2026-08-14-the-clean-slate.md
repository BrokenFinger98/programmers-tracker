---
type: source
project: programmers-tracker
tags: [vault, obsidian, seeds, git, coverage, ci, failed-attempts]
created: 2026-08-14
updated: 2026-10-08
sources: [raw/sessions/2026-08-14-the-clean-slate.md]
---

# 2026-08-14 session summary — the clean slate

Recovered from `raw/inbox/` during the 2026-08-14 ingest. The segment was worked and merged on the
day and never became a session page; a check before deleting the snapshot caught it, because
`#300`, `#304`, `#306` and `SeedLedger` appeared in no raw page. It continues
[[sources/2026-08-14-the-night-the-records-learned-the-question]] from the point where the owner
had read the vault in Obsidian and asked for the accumulated test data to be wiped, so the tool
could be met as a first-time user meets it.

## Key claims

1. **The statement moves into the problem page.** #275 chose an embed over inlining because the
   problem README is regenerated on every grading
   ([[decisions/2026-08-13-the-statement-travels-with-the-record]]); once `statement.md` existed as
   a file, inlining cost a read. #293 had replaced the embed with a markdown link, which cost one
   page; inlining gives it back in all three renderers. `statement.md` stays — it is the source and
   the page is derived, and deleting the source leaves a request to Programmers as the only way
   back. The statement goes **last**, against what the issue first proposed: a KAKAO statement runs
   several kilobytes, and the attempt history is the half a record is opened for. The new cost was
   named rather than discovered: the owner's graph screenshot showed six `statement` nodes, which
   become isolated dots once the link is gone. The same screenshot closed #293's open question —
   markdown links **do** draw edges in Obsidian.
2. **#300 — the third state a seeded file can be in.** The seeds are written if absent, on a rule
   that did not change: editing is respected forever, deletion is read as loss, not intent
   ([[decisions/2026-08-13-the-server-prepares-the-repository]]). The third state is **untouched,
   and stale**. `SeedLedger` records the SHA-256 of what the server wrote in `.ps/seeds.json`; a
   file that still hashes to it is ours to replace, one character different and it is theirs,
   permanently. **No record means edited** — a vault seeded before the ledger has none, and absence
   is not permission — and a corrupt ledger reads as empty for the same reason. Rejected: shipping
   the bytes of every version ever seeded (unbounded growth, and a release process this project
   does not have), and a marker inside the file (visible in a document meant to be read, and
   deletable by accident).
3. **The runner exemption, 66% → 78%** (#272 follow-up). `domain/calc/runner` was the largest
   untested surface in the repository, and #285 had already corrected why: executing generated
   code proves it correct without visiting a new path, so the uncovered branches are generation
   branches ([[decisions/2026-08-13-a-floor-per-package-and-a-reason-per-exception]]). Two
   table-driven suites took it to 679/862 — table-driven so that a language missing from the list
   shows up as a gap, the shape the compile-error fixtures already use after #212
   ([[decisions/2026-08-12-a-language-is-supported-when-its-failures-are-too]]).
4. **#298 — the one CI job that downloaded the world every run.** Six of seven jobs cache
   `~/.gradle`; `docker image boots` could not, because the Gradle that matters runs inside the
   image. It had already cost a 429 from Maven Central on one run: a job that re-downloads
   everything is one rate limit away from a red build that says nothing about the code. It is now
   built through `docker/build-push-action` with a `type=gha` layer cache.
5. **#304/#306 — the server ignored one editor's directory and committed the other's.** Measured on
   the owner's repository, `.idea/` was **tracked and pushed**, beside `problems/` and `log/`. The
   argument that added `.obsidian/` never mentioned Obsidian: `git add --all` cannot tell an
   editor's state from a record, and the server is the one committing
   ([[decisions/2026-08-13-the-vault-is-not-only-records]]). The new rule does not untrack what is
   already committed; a server running `git rm --cached` on a user's repository would be an ignore
   rule grown into something else. `.obsidian/` itself was narrowed to `workspace.json` and its
   mobile twin, and a rule the reader already wrote now wins: `alreadyIgnores` checks ancestors
   instead of matching a line exactly. The narrowing could not reach a repository created before
   it, which became #308 ([[sources/2026-08-14-the-warnings-and-what-was-under-them]]).
6. **The wipe made the next boot a first boot.** What survived: `tags/` (83 notes, all
   `untouched`), the three seeds, `.obsidian/` (now versioned), `.gitignore`. The first boot logged
   `Added [.idea/]`, three seed writes, three hashes recorded by `SeedLedger`, no orphan-frame
   warning, and `recorded=0`. Nothing had been solved into it yet — the gap
   [[sources/2026-08-14-the-first-run-test-and-what-it-found]] closed.

## What turned out wrong

⚠️ **My objection to inlining had expired.** It rested on the README being regenerated, and stopped
being true the moment `statement.md` was a file on disk. It surfaced when the owner asked why the
statement was a link rather than the text itself.

⚠️ **The seed rule considered two states, absent and edited.** Twice on 2026-08-13 an improvement
reached the shipped seed and the one existing vault only because somebody edited it by hand — the
graph's `kind` colour groups (#271) and the `Kind` column (#274). And the premise the ledger rests
on, *byte-identical means nobody touched it*, was falsified for `dashboard.base` the same week:
[[decisions/2026-08-14-the-seed-ships-in-the-form-its-reader-rewrites-it-to]].

⚠️ **Both runner tables failed on their first run, because I asserted a refusal the code does not
make.** Twice: a quoted number for a numeric parameter (`int` ← `"7"`) is accepted and rendered `7`;
an unquoted number as stdin is accepted and fed as its own text, while the refusal message three
lines away says "quoted text". Neither shape is measured — no `examples.json` and no fixture has
ever carried either — so the code was not changed on a guess: both are pinned as the behaviour
that exists, labelled *never measured*. The third and fourth time in two days that an assertion of
mine turned out to be an assumption ([[concepts/assumption-vs-measurement]]); this time it reached a
test and nothing else.

⚠️ **The blanket `.obsidian/` rule threw away the valuable half.** The history, file by file:
`workspace.json` changed 5 times because Obsidian was opened, `graph.json` 5 times because somebody
configured the graph. #234's complaint was noise, and the rule answered it by discarding the
configuration too, in a tool whose whole argument is that nothing is lost.
