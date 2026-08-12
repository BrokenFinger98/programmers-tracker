---
type: source
project: programmers-tracker
tags: [mcp, measurement, vault, discipline, failed-attempts]
created: 2026-08-13
updated: 2026-08-13
sources: [raw/sessions/2026-08-13-the-tally-that-counted-runs.md]
---

# 2026-08-13 session summary — the vault verified, and two tools that disagreed

## Key claims

1. **The tag map was verified against the running server, not against its tests.** 83 notes,
   **514** wikilinks, **0** dangling; and **0 of 83** notes disagreed with the records when the
   calculator was reimplemented in another language and the answers diffed.
2. The link count matched a prediction made before the deploy (510 tag→tag + 4 problem→tag),
   which is the cheapest confidence there is: arithmetic first, world afterwards.
3. **#233's number was overstated and was corrected before merge.** *43 of 83 links resolved to
   nothing* was really *43 of 83 tags could not be linked to*. The vault held 4 links, none to a
   slugged tag, so nothing on disk was broken; #232 would have dangled 178 of 510. Latent, not
   live — see [[concepts/tests-that-explain-defects]].
4. **Two MCP tools gave different answers for one problem** — `list_problems` 8 attempts,
   `stats` 15 — because `stats` counted runs as submissions (#235). Its verdict tally therefore
   showed 7 compile errors and 2 runtime errors, **every one of them a run**, against 11
   submissions of which 10 passed.
5. **The rule existed in four places and was missing from the fifth.** `ReviewQueue`,
   `SlowPasses`, the tag map and `CatalogBrowse` all test `action == SUBMIT`; `SubmissionTally`
   counted whatever `RecordQuery.history()` handed it. Recorded in
   [[concepts/assumption-vs-measurement]] as the invariant-by-convention variant.
6. **No test pinned the old behaviour**, because every tally test used the fixture's default
   action. Counting runs was never a decision — it was what reading the history happened to do.
7. **Ask two consumers of one dataset the same question.** From inside a call site, every test
   agrees with the code; a second consumer is an outside reference, like the filesystem was the
   night before.
8. `reconcile()` is `git add --all`, so the 23:00 backup committed nothing but Obsidian's editor
   state under a message about records (#234). **Filed, not fixed** — narrowing the staging scope
   trades a visible annoyance for the invisible failure the safety net exists to prevent.
9. The daily backup was **observed firing on its own** for the first time: 14:00:29Z = 23:00 KST,
   exactly as configured, reconciled, committed, pushed.
