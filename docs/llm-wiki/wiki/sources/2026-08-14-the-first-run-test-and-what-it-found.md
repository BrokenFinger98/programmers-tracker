---
type: source
project: programmers-tracker
tags: [measurement, coverage, git, obsidian, records, ci, failed-attempts]
created: 2026-08-14
updated: 2026-10-08
sources: [raw/sessions/2026-08-14-the-first-run-test-and-what-it-found.md]
---

# 2026-08-14 session summary — the first-run test, and the two defects it found

Continues [[sources/2026-08-14-the-warnings-and-what-was-under-them]] from the owner's correction:
the remaining risks were mine to close, and the browser-driven first-run test was something the
owner had already told me to do myself. **Listing a risk is not reporting it; it is deferring it.**
Six PRs merged. The last coverage exemption was retired, two Lv0 problems were solved against the
blank vault the wipe had left ([[sources/2026-08-14-the-clean-slate]]), and the test found two
defects: a pass whose push carried everything except the solution (#316), and a seed that
Obsidian rewrites when it renders the view (#314).

## Key claims

1. **#302 — the exemption is retired, not raised.** `coverageExempt` is now empty, and every package
   in the tree carries an enforced floor
   ([[decisions/2026-08-13-a-floor-per-package-and-a-reason-per-exception]]). C needed its own
   table because one wire value is not one parameter — `int n[], size_t n_len`;
   `int** n, size_t n_rows, size_t n_cols`. 54 cases, weighted to refusals, because in C a
   mis-sized array is not a wrong answer, it is a read past the end of a block. **54/54 passed on
   the first run**, which the two previous tables did not; the difference was reading `CRunner` end
   to end and predicting each arm before writing the assertion. It was closable because #283
   forced the exempt row to keep printing, so the distance left was a fact on every CI run instead
   of a promise in a comment.
2. **The whole path ran from nothing for the first time.** 120811 (run, then submit) and 120802
   (submit only), solved through the browser: `/watch` → `timers.json` → statement fetch and
   HTML→Markdown → `examples.json` → runner generation → code → `attempts/` → verdict → tag notes →
   index → commit → push. PASS 9/9 and 18/18.
3. **A live counterexample for where examples come from.** On 120811 the statement's examples table
   reads `[1, 2, 7, 10, 11] → 7`, and the judge's own sample testcase is `[1, 2, 3, 4, 5] → 3`.
   `examples.json` captured the judge's, which is correct and is what the runner must test
   ([[decisions/2026-08-07-server-generated-runners]]). Reading examples from the judge's data
   rather than parsing the statement table had been decided without a known counterexample; there
   is one now, on a problem anybody can open.
4. **A run saves the source** — the JSONL answered the owner's question directly. A run's
   `codePath` is `problems/…/Solution.java`, a submit's `problems/…/attempts/001.java`: a run writes
   the live `Solution.<ext>`, and what it does not create is `attempts/NNN.*` (design §5.1).
5. **#316 — the push a pass triggered did not contain the solution.** The commit for 120811's PASS
   held `submissions.jsonl` and `001.raw.jsonl` and nothing else. The solution, statement, runner,
   problem page, index and both tag notes were untracked when `pushOnPass` fired and would have
   waited for the 23:00 backup — up to ~24 hours in which the off-machine copy of a solved problem
   has no solution in it. Not a bug in the commit: `pathsOf` is scoped to the log and the frames on
   purpose, and `CodeAttachment` writes the rest after fetching the source. `commitScoped`'s KDoc
   already said those writes ride along with the next reconciliation; what it did not account for
   is that **a pass is what triggers the push**, so the one moment the design promises an
   off-machine copy is the moment that copy has the least in it. The push is now **added**, not
   moved: [[decisions/2026-08-14-the-push-waits-for-the-fetch-the-commit-does-not]].
   Verified live: 22 s after a PASS the remote HEAD carried `Solution.java`, `attempts/001.java`,
   `statement.md` and the problem page.
6. **#314 — Obsidian rewrote the seed, and the ledger locked behind it.** `dashboard.base` changed
   on disk while the vault sat open: deletions only, 15 comment lines to 0, zero additions.
   `SeedLedger` then reads the file as edited and never updates that vault's dashboard again — for
   a reader who edited nothing. **Rendering the Base view is the trigger.** The fix ships
   Obsidian's own output, a fixed point of its transform — measured, not assumed, since the same
   hash came out of the same input twice — moves the comments' content to the vault README, and
   lets `VaultDashboard.adopted` claim a file whose bytes already equal what would be written
   ([[decisions/2026-08-14-the-seed-ships-in-the-form-its-reader-rewrites-it-to]]). Verified live:
   after the rebuild the owner's ledger moved `9fc9640…` → `a3967cd…` with the file unchanged, and
   the new README section arrived.
7. **#319 — a toolchain nobody declared.** `gates (windows-latest)` failed once at *the runner
   execution proofs genuinely ran*: `dotnet` was absent, `CsharpRunnerExecutionTest` skipped 8, and
   the guard fired. A plain re-run passed. The guard did its job and should not be loosened; what
   it revealed is that `dotnet` is the one toolchain the workflow does not declare, used because the
   image happens to ship it. A red build on an unrelated PR is how a real regression gets re-run
   away as "flaky".

## What turned out wrong

⚠️ **#302's estimate.** Measured before anything was written, and the issue was wrong: it put ~30
branches left in C and said `gridLocals` could not be reached from a single-parameter table.
Kover's method counters said **47**, `CRunnerTest` had been reaching `gridLocals` all along, and the
real gap was almost entirely refusals. Writing to the estimate would have produced happy-path tests
for branches that were already green.

⚠️ **I nearly reported a designed behaviour as a defect.** The log showed each record twice with
identical nanosecond timestamps, which looked like a double write. It is the `codePending`
correction append ([[decisions/2026-08-05-code-pending-correction-append]]), and the two lines
differ in `codePending` and `codePath` — the two fields my first printout happened to omit.

⚠️ **I recommended moving the push, and reversed it while implementing.** The reason was already in
the repository, in `copiedRawPath`: *"the verdict is unrecoverable and the copy is not"*. Moving the
push makes the unrecoverable half wait on a network fetch of the recoverable half — a milder form
of the option that had just been rejected — and since `CodeFetch` has terminal outcomes, an expired
session would not delay the verdict commit, it would prevent it.

⚠️ **The test's first failure was the fixture, not the code.** Asserting at the remote showed the
bare repository escaping every Korean problem directory (`problems/120804-\353\221\220…`).
`GitWorkspace` has set `core.quotePath=false` on the working repository since it was written, with
a comment naming this exact trap; the bare remote never got it, because until then nothing had
read paths off a remote rather than commit subjects.

⚠️ **My reproduction of #314 failed, and I corrected the issue before it was confirmed.** I had
written that the trigger was having the vault open; restoring the commented file and waiting 75
minutes with Obsidian running changed nothing. I struck the claim, said what was still measured
and what was not, and asked for the one step this side cannot take. The owner opened the Base view
and the hash moved inside the minute, to byte-for-byte the output seen earlier.

The reversed push and the failed reproduction are the two cases
[[concepts/assumption-vs-measurement]] keeps from this session.

## What found each thing

Again outside references, not re-reading the code:

| Found by | What it found |
|---|---|
| The owner's own build output | a warning on every build since #295, which nobody had read (#309) |
| Removing that warning | the preview-API acceptance underneath it (#310) |
| Kover's method counters | 47 branches in C, not the issue's 30, and in refusals not happy paths (#302) |
| Solving a problem from a blank vault | a pass pushing everything except the solution (#316) |
| The owner opening one file | the trigger my own reproduction had failed to find (#314) |
| One red Windows job | a toolchain the workflow never declared (#319) |
