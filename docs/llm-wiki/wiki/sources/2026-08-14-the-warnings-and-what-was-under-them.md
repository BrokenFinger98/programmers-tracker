---
type: source
project: programmers-tracker
tags: [tooling, testing, coroutines, git, discipline, failed-attempts]
created: 2026-08-14
updated: 2026-10-08
sources: [raw/sessions/2026-08-14-the-warnings-and-what-was-under-them.md]
---

# 2026-08-14 session summary — the warnings, and what was under them

The same session as [[sources/2026-08-14-the-night-the-records-learned-the-question]], from the
point where the owner wiped the record repository to test as a first-time user and rebuilt the
container themselves. The build printed two warnings, and **both turned out to be about something
other than the warning**. The pattern of the whole session held once more: the finding came from
an outside reference, the owner's terminal, and not from re-reading the code that produced it.

## Key claims

1. **The owner's own build output was the finding.** The rebuild was the owner's command, and its
   output carried a warning written by me the previous day, in #295:
   `ProblemIndex.kt:79:33 Unnecessary safe call on a non-null receiver of type 'String'`.
2. **The safe call was standing on a live, untested fallback.** `?.` on a non-null `String` is a
   no-op; the expression under it is not:
   `record.title.ifBlank { null } ?: record.lessonId.toString()`.
   `SettledCapture.toRecord` writes `title = problem?.title.orEmpty()`, so a problem the cached
   catalog has never seen records an empty title — blank is how "unknown" is spelled in the JSONL —
   and `RecordLayout` names its directory `<lessonId>` with no slug. The index row links by the id
   rather than rendering `[](...)`. Pinned in #309/#311.
3. **Removing one warning made the other one visible.** With #309 merged the build printed exactly
   one line, `Flow.timeout is @FlowPreview` in `CableChannelSubscriber`: the silence deadline on
   the observation socket, the only thing that notices a channel has gone quiet. It was
   deliberately not annotated inside the typo fix. `@OptIn` suppresses nothing; it is a written
   acceptance that a coroutines upgrade may break that line, which is a decision with an ADR
   attached. Filed as #310 and resolved separately:
   [[decisions/2026-08-14-a-preview-api-under-a-test]].
4. **The deciding fact was found by looking for a test, not by reasoning about risk.**
   `heartbeats hold the socket open and never reach the capture` runs a 200 ms deadline against a
   flow emitting for 600 ms and asserts zero reconnects. A silent change of meaning — gap between
   emissions to total collection time — turns that test red, and removal of the API is a compile
   failure. Both failure modes are caught, so the acceptance is affordable; without that test it
   would not have been.
5. **#308 — an improvement that could not reach an existing install.** #307 narrowed `.obsidian/`
   to `.obsidian/workspace.json` ([[sources/2026-08-14-the-clean-slate]]), and
   `RecordRepositoryIgnores` adds a missing rule and edits nothing, so the narrowing reached only
   repositories created after it. Same shape as #300, **different answer**: the seeds got a ledger
   because seeds change often (three improvements in two days); ignore rules do not (five in the
   tool's history, one ever narrowed). So the answer is a sentence in `docs/bootstrap.md`, and the
   rule's own comment already carried the instruction before anybody needed it. The owner's
   repository was already correct: the wipe had rewritten its `.gitignore` from scratch, so the
   by-hand deletion the issue asked for had happened by accident.

## What turned out wrong

⚠️ **Nobody had read the warning.** It had printed on every build since #295 — including the dozen
builds I ran myself.

⚠️ **The fallback had never been executed by a test.** Every case in `ProblemIndexTest` passed a
real title, so the one path an uncatalogued problem's index row takes was untested until the
warning led to it.

⚠️ **The fix for one problem guaranteed the other.** The ancestor-aware `alreadyIgnores` came in the
*same* change as the narrowing, and it made the server correctly decline to add the narrower rule
underneath the broader one already there.

⚠️ **I listed a risk instead of closing it.** I closed the report with three remaining risks and
handed the last one back: it would close, I wrote, when the owner solved a problem. The answer, in
translation: *"Aren't the remaining risks yours to resolve?? You want me to solve a problem???"*
Both halves land. The risks were mine to close, and earlier in the same session the owner had
already told me to drive the browser and solve one myself. **Listing a risk is not reporting; it is
deferring.** The test that followed is [[sources/2026-08-14-the-first-run-test-and-what-it-found]].
