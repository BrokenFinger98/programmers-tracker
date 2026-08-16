# 2026-08-14 — the first-run test, and the two defects it found

Raw session record. Immutable (wiki schema §1). Continues
`2026-08-14-the-warnings-and-what-was-under-them.md`, from the owner's correction:

> 남은 위험 해결해야하는거 아니야?? 문제 하나 풀라고???

Both halves landed. The remaining risks were mine to close, and the browser-driven first-run test
was something the owner had already told me to do myself earlier in the same session
(*"너가 직접 브라우저 조작해서 문제 풀면되잖아"*). **Listing a risk is not reporting it; it is
deferring it.**

---

## #302 — the exemption is retired, not raised

`coverageExempt` is now **empty**. Every package in the tree carries an enforced floor.

**Measured before writing anything, and the issue was wrong.** #302 estimated ~30 branches left in
C and said `gridLocals` could not be reached from a single-parameter table. Kover's method
counters said **47**, and `CRunnerTest` had been reaching `gridLocals` all along. The real gap was
somewhere else entirely — `scalarLiteral` 9, `gridLocals` 7, `intLiteral` 7, `checkFor` 5,
`arrayLocals`/`textLiteral`/`blockFor` 4 each — and **almost entirely refusals**. Writing to the
issue's estimate would have produced happy-path tests for branches that were already green.

C needed its own table for a reason the other six do not have: **one wire value is not one
parameter.** `int n[], size_t n_len`; `int** n, size_t n_rows, size_t n_cols`. A table keyed on
*one declared type, one value* cannot express that.

54 cases, weighted to refusals, because in C being wrong is worse than elsewhere: a mis-sized
array is not a wrong answer, it is a read past the end of a block. The grid refusals carry the
most — `_rows` and `_cols` are written once from the first row, so a ragged value would hand the
solution a `cols` that is a lie for every row after the first.

**54/54 passed on the first run**, which the two previous tables did not. The difference was
reading `CRunner` end to end and predicting each arm before writing the assertion.

What made this closable is that the number was visible. *"Raising it is tracked separately"* is the
sentence that usually makes an exemption permanent; it survived here only because #283 forced the
exempt row to keep printing, so the distance left was a fact on every CI run instead of a promise
in a comment. Outcome recorded in
[[decisions/2026-08-13-a-floor-per-package-and-a-reason-per-exception]].

## The first-run test, driven from this side

Two Lv0 problems solved through the browser against the blank vault: **120811 중앙값 구하기**
(run, then submit) and **120802 두 수의 합 구하기** (submit only).

The whole path ran for the first time from nothing: `/watch` → `timers.json` → statement fetch and
HTML→Markdown → `examples.json` → runner generation → code → `attempts/` → verdict → tag notes →
index → commit → push. PASS 9/9 and 18/18.

### A live counterexample for where examples come from

On 120811 the statement's 입출력 예 table reads `[1, 2, 7, 10, 11] → 7`, and the judge's own
sample testcase is `[1, 2, 3, 4, 5] → 3`. **Programmers' two are different for this problem.**

`examples.json` captured the judge's, which is correct and is what the runner must test. The
decision to read examples from the judge's data rather than parse the statement table had been
made without a known counterexample; there is one now, and it is a problem anybody can open.

### A question the record answered about itself

The owner asked whether a *run* saves the source. It does — the JSONL says so directly:

| action | `codePath` |
|---|---|
| run | `problems/…/Solution.java` |
| submit | `problems/…/attempts/001.java` |

A run writes the live `Solution.<ext>`; what it does not create is `attempts/NNN.*` (design §5.1).

**And I nearly reported a designed behaviour as a defect.** The log showed each record twice with
identical nanosecond timestamps, which looked like a double write. It is the `codePending`
correction append — ADR `2026-08-05-code-pending-correction-append` — and the two lines differ in
`codePending` and `codePath`, the two fields my first printout happened to omit.

## #316 — the push a pass triggered did not contain the solution

The commit `[Lv0] 중앙값 구하기 — PASS (9/9…)` held **`submissions.jsonl` and `001.raw.jsonl` and
nothing else**. The solution, statement, runner, problem page, index and both tag notes were
untracked when `pushOnPass` fired, and would have waited for the 23:00 backup — up to ~24 hours in
which the off-machine copy of a solved problem has no solution in it.

Not a bug in the commit: `pathsOf` is scoped to the log and the frames on purpose, because those
are what exists the moment a grading settles. `CodeAttachment` writes the rest **after** fetching
the source. `commitScoped`'s KDoc already said those writes ride along with the next
reconciliation; what it did not account for is that **a pass is what triggers the push**, so the
one moment the design promises an off-machine copy is the moment that copy has the least in it.

**I recommended moving the push, and reversed it while implementing.** The reason was already in
the repository — `copiedRawPath`: *"the verdict is unrecoverable and the copy is not"*. Moving the
push makes the unrecoverable half wait on a network fetch of the recoverable half, which is a
milder form of the option that had just been rejected. And `CodeFetch` has terminal outcomes, so
an expired session would not delay the verdict commit — it would prevent it.

So the push is **added**, not moved. Decision and costs:
[[decisions/2026-08-14-the-push-waits-for-the-fetch-the-commit-does-not]].

**The test's first failure was the fixture, not the code.** Asserting at the remote — where the
defect lives — showed the bare repository escaping every Korean problem directory
(`problems/120804-\353\221\220…`). `GitWorkspace` has set `core.quotePath=false` on the working
repository since it was written, *with a comment naming this exact trap*; the bare remote never got
it, because until then nothing had read paths off a remote rather than commit subjects.

Verified live afterwards: 22 s after a PASS, the remote HEAD had moved and carried `Solution.java`,
`attempts/001.java`, `statement.md` and the problem page.

## #314 — Obsidian rewrote the seed, and the ledger locked behind it

`dashboard.base` changed on disk while the vault sat open. Diff: **deletions only, 15 comment
lines to 0, zero additions.** `SeedLedger` then reads the file as edited and never updates that
vault's dashboard again — for a reader who edited nothing.

**My reproduction failed, and I corrected the issue before it was confirmed.** I had written that
the trigger was "having the vault open"; restoring the commented file and waiting 75 minutes with
Obsidian running changed nothing. I struck the claim and said what was still measured and what was
not — then asked for the one step this side cannot take. The owner opened the dashboard Base view
and the hash moved inside the minute, to byte-for-byte the output seen earlier. **Rendering the
view is the trigger.**

Fixed by shipping Obsidian's own output: deletions only and nothing left to delete, so that form
is a fixed point of its transform — measured, not assumed, since the same hash came out of the
same input twice. The comments' content moved to the vault README, which survives being read.

The half that makes it reach existing vaults is `VaultDashboard.adopted`: a file whose bytes
already equal what we would write is ours, whatever the ledger remembers. It is the one claim of
ownership that cannot cost an edit — there is none, and writing would be a no-op. Decision:
[[decisions/2026-08-14-the-seed-ships-in-the-form-its-reader-rewrites-it-to]].

Verified live: after the rebuild the owner's ledger moved `9fc9640…` → `a3967cd…` with the file
unchanged, and the new README section arrived.

## #319 — a toolchain nobody declared

`gates (windows-latest)` failed once at *the runner execution proofs genuinely ran*: `dotnet` was
absent, `CsharpRunnerExecutionTest` skipped 8, the guard fired. A plain re-run passed.

**The guard did its job and should not be loosened** — it exists so a runner image quietly dropping
a toolchain cannot silently un-earn a language's supported status. What it revealed is upstream:
`dotnet` is the one toolchain the workflow does not declare, used because the image happens to
ship it. The failure mode is a red build on an unrelated PR, which is how a real regression gets
re-run away as "flaky".

## The shape of the day

Six PRs merged. The findings again came from outside references rather than from re-reading code:

| Found by | What it found |
|---|---|
| The owner's own build output | a warning on every build since #295, which nobody had read (#309) |
| Removing that warning | the preview-API acceptance underneath it (#310) |
| Kover's method counters | 47 branches in C, not the issue's 30, and in refusals not happy paths (#302) |
| Solving a problem from a blank vault | a pass pushing everything except the solution (#316) |
| The owner opening one file | the trigger my own reproduction had failed to find (#314) |
| One red Windows job | a toolchain the workflow never declared (#319) |
