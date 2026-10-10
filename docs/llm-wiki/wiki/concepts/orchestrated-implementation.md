---
type: concept
project: programmers-tracker
tags: [orchestration, workflow, testing, debugging-pattern, subagents]
created: 2026-08-05
updated: 2026-10-10
sources: [raw/sessions/2026-08-05-design-review-and-stack-upgrade.md, raw/sessions/2026-08-12-two-workers-that-never-started.md, raw/sessions/2026-10-07-repairs-not-verdicts.md, raw/sessions/2026-10-07-the-readers-that-followed-links.md, raw/sessions/2026-10-08-the-prompt-only-the-owner-can-run.md, raw/sessions/2026-10-08-the-token-gate-took-four-rounds.md, raw/sessions/2026-10-08-seventeen-prs-through-one-queue.md, raw/sessions/2026-10-10-the-limit-the-load-and-the-last-five.md]
---

# Building This Project With Supervised Workers

Most of the implementation (#6, #16, #18) was built by dispatching supervised Orca workers
rather than writing everything in one session. Three failure modes recurred often enough to
be procedure rather than anecdote.

## 1. A dispatched prompt can sit unsubmitted

**Every worker started so far** has landed its task text in the agent's composer without
submitting it. The tell is unambiguous: the status line reads `$0.00 session` and `🧠 0`
while the terminal shows the full prompt. Nothing is running, and a coordinator that waits
for `worker_done` waits forever.

The fix is one keystroke — `orca terminal send --terminal <handle> --text "" --enter` — so
the cost is entirely in *noticing*. Treat it as part of starting a worker:

```
worker-start → wait ~20 s → read the terminal → if no activity marker, send a bare Enter
```

First seen 2026-08-04 ([[sources/2026-08-04-oss-workflow]]), and in every dispatch since.

**The harder variant (2026-08-12): a multi-line prompt is split by the composer.** Each newline
submits a fragment, the remainder sits unsent, and a bare Enter now submits *garbage* rather than
the task. The dispatch layer reported `ready` for fifteen minutes throughout — **state is what
the dispatch layer believes; the terminal is what is true**, and only reading the terminal said
the agent had never received a task. Rule: task specs are one line, or a path to a file the
worker reads (raw/sessions/2026-08-12-two-workers-that-never-started.md).

**And the stopping rule, from the same evening.** The clean one-line re-dispatch was delivered
intact and still never answered — no error, no heartbeat, cause never established and
deliberately not guessed. After one diagnosed failure and one undiagnosable one, the third
attempt is direct execution, not a third dispatch. The owner called it first ("그냥 너가 직접
해라"), and the twenty-eight minutes bought exactly three reusable facts: the composer
constraint, state-vs-terminal, and this rule.

## 2. Disjoint files still share a build

Issue #18's workers A and B touched entirely separate packages, which felt safe. It was
not: they shared one worktree, so B's half-written `adapter/store` files broke A's
`./gradlew` run. A only finished because it worked around the problem — running its gates
against `git archive HEAD` in a scratch directory.

**Files not overlapping does not mean builds do not overlap.** Anything that compiles
concurrently needs an isolated worktree or sequencing; only genuinely non-compiling work
(documentation, fixtures) is safe to parallelize in a shared checkout.

## 3. A worker's completion report can be rejected while its work is real

On #6 a worker finished correctly, but its `worker_done` was refused with
`dispatch_capability_invalid` because a manual re-prompt had not carried the capability
token. The commit existed; only the provenance was broken.

The recovery is to verify the artifacts directly and then close the task with an explicit
recovery note — never to describe a report that never arrived as if it had.

## 4. Disagreeing workers are a detector for undecided rules

On #22 two workers read the same documents and reached opposite conclusions. One keyed the
subscription registry by a `protocol` type from inside `application`; the other wrote in a
KDoc that `application` must not depend on `protocol`. Both were defensible: the first
followed a precedent already merged in #16, the second followed `development-rules` §1 as
written.

Neither was wrong about the codebase — **the codebase was inconsistent**, and had been since
#16 passed review. A single author would very likely have picked one reading and stayed
consistent with themselves, and the contradiction would have kept compiling.

Two independent readers of the same rules are therefore a cheap consistency check on the
rules themselves. When workers disagree about *style*, that is noise; when they disagree
about *what a rule means*, the rule is what needs attention, not the code. This one became
issue #24 with both options written out, rather than a silent third convention invented
during integration.

## 5. Subagents instead of workers, and the instructions they copy

On 2026-10-07 the owner was offered orchestration for the mistake-patterns build. They chose
in-session subagents instead: "don't orchestrate; try subagents, then." In under eighteen hours
this produced six code PRs (#348, #350, #352, #357, #363 and #367). Every task went to a fresh
implementer, then a spec-compliance reviewer, then a code-quality reviewer; each branch ended with a
whole-branch review (raw/sessions/2026-10-07-repairs-not-verdicts.md).

- **Parallel only where nothing is written.** Reviews ran side by side because they only read.
  Implementers ran one at a time: every task touched `progress.md`, the plan or the ADR, the files
  parallel PRs had reverted before.
- **Review found what the tests could not.** Seven examples:
  - the client's 2,048-character cut;
  - three rounds on a code-read bound;
  - refusals that were silent;
  - a log capture that could not hear throwables;
  - a non-object `arguments` that widened the scope;
  - one SQL rejection filed as two verdicts;
  - a command name that matched nothing.

  None of these was a failing test. Each was a reviewer reading a real answer, the client's code, or
  the repository's rules.
- **Interruption.** A session ended under a running implementer, and its fixes were left written but
  not committed. Recovery was the August rule: verify the artifact, not the report. The controller
  ran the gates, then reverted two fixes on purpose to watch the new tests fail. After that, every
  task committed as it went.
- **The agent type matters.** A reviewer of a read-only type approved without running tests, so the
  controller ran them.
- **The scratchpad is shared.** One implementer's mutation helper restored another agent's leftover
  `.orig` backup over a source file. It happened to be identical to HEAD. Re-hashing all 601 tracked
  files proved that, where the agent's report alone could not.
- **Workers copy the controller's mistakes.** The repository's commit skill forbids AI trailers. The
  controller's own instructions carried one, so every implementer commit did too. So did 117 of the
  180 commits on main, going back to the first one. Nobody had checked them against the skill until
  a spec reviewer read it (raw/sessions/2026-10-07-the-readers-that-followed-links.md). A reviewer
  that reads the repository's rules, independently of the brief, is the only check on the brief
  itself.
- **The overnight stretch kept the same discipline.** It ran from 23:41 to 02:36 while the owner
  slept. Every merge waited on CI, and every claim waited on a review. The one acceptance step that
  needs a person, running the `exam_prep` prompt, was left pending rather than worked around
  (raw/sessions/2026-10-08-the-prompt-only-the-owner-can-run.md).

## 6. One merge queue, many subagents

24 PRs merged between 09:57 on 2026-10-08 and 23:40 on 2026-10-10. They closed 25 issues, 17 of them
filed during that window from reviews and implementers' reports. Each issue had its own implementer in
its own git worktree. Critics read commits through `git archive`, and only the coordinator pushed and
merged (raw/sessions/2026-10-08-the-token-gate-took-four-rounds.md,
raw/sessions/2026-10-08-seventeen-prs-through-one-queue.md,
raw/sessions/2026-10-10-the-limit-the-load-and-the-last-five.md).

- **Adversarial review found a real defect in almost every PR it read.** Eleven PRs met an adversarial
  critic. Nine left with a measured finding that was fixed before the merge or filed:
  - **#379:** four rounds. Each of the first three found a High (F1–F4, N2, U1), and the third also
    had a Critical race, from the quality reviewer, that the coordinator's own design had caused.
  - **#391:** S1–S5; four were fixed in the branch and two filed.
  - **#395:** M1, M2 and M4, and L1–L5; M3 went to #378, and the re-check found one more Medium.
  - **#396:** a Medium regression the PR itself introduced.
  - **#398:** blocked, by a pre-existing High that its own audit had called harmless, plus a Medium and
    two Lows.
  - **#400:** a pre-existing Medium already in #376's scope, and a Low that #402 later fixed.
  - **#401:** a race-only Medium and a Low.
  - **#404:** two findings, filed as #405.
  - **#410:** a Medium, every commit refused on git 2.39, and a Low on how push URLs are read, in four
    cases.

  The other two, #408 and #409, drew only race-only or local-process Lows, which were accepted. The
  five quality reviews (#389, #393, #399, #406, #411) all approved with nothing blocking, and every one
  still changed its branch. CI's other machines then found what no reviewer had, in tests and once in the
  product. The October 8–10 section of the assumption-vs-measurement page lists them.
- **The critic who found it checks the fix.** Long-lived agents carried related issues. One critic
  attacked the token gate in #360, #373, #375, #376 and #410, and the writers in #361 and #374. One
  implementer took #373, #375 and #374. Each re-verification went back to whoever had found the defect:
  U1, the orphans hang, N10. A completion
  notice still carries the agent's first task name ("Implement #373" reported #374), so it is a handle,
  not a description.
- **A blocking rule, stated before the pass.** After #360's third round, the coordinator wrote down what
  blocks a merge: the server's own leak, or data lost on a healthy repository. Everything else became an
  issue. That is what let four rounds end, and what turned the remainder into 17 issues rather than one
  endless PR. The rule bounds what blocks, not what gets fixed. #373's regression was Medium, so "not
  blocking", and it still did not merge until it was fixed: a known regression does not go into a token
  gate.
- **Once pushed, merge; never rebase.** Force pushes to three PR branches went out as `git -C <dir> push
  --force-with-lease`. The owner's hook matches `git push … --force`, and the `-C` between the two words
  slipped past it. A plain spelling was blocked later the same day. The rule since: a pushed PR branch
  gets `origin/main` merged in and a plain push, and a hook's pattern is never stepped around. Also,
  GitHub's mergeability check runs no custom merge driver, so a PR that `merge=union` resolves locally
  can show a conflict there. Each next PR is brought up to date locally before its merge.
- **A squash-merged base conflicts with what is stacked on it.** An upper branch that merged the lower
  one's head holds the lower's commits; the squash is a different commit with the same tree. Merging main
  then conflicts wherever the upper branch re-edited the lower's lines. #375 met six such conflicts, all
  resolved to the branch's version, a merge that changed no file. The coordinator had predicted a clean
  merge. Before the first push the cure is to move the branch: `git rebase --onto origin/main <old base>`
  replays only its own commits (#390, #378, #386, #374). After the first push, merge.
- **One conflict, one resolver.** Two agents began resolving the same seven hunks. One was stopped, and
  the single resolution was handed down: #387, then #386, then #374. Two resolutions of one conflict
  diverge, then conflict with each other.
- **An interruption keeps its state only if the state is written down.** When the owner left work, every
  agent was stopped. Each issue's branch, worktree, agent and next step went into `goal.md`, and the
  scratch tools were copied out of `/tmp`. On resume each agent was told exactly where it had stopped. The
  weekly usage limit cut three agents off with HTTP 429 mid-task at 23:51, and was handled the same way
  when the session resumed 40 hours later. A worktree that another agent had taken over in the meantime
  was replaced, for its reviewer, by `git archive <sha>`.
- **Load looks like failure.** macOS's XProtect at 773% CPU, with a load average of 64–82, made each
  process spawn take minutes. Agents tripped the 600 s stream watchdog three times, and a push killed at
  the 30-minute limit turned out to have succeeded. Retrying would have added load. The fix was to wait
  on a load monitor, then run every Gradle command in the background with a long timeout, one at a time.

## Tests for things that loop forever

Three CI failures in a row on this project came from test design, not from the code under
test, and two of them share a cause: the thing being tested is **designed never to finish**.

A reconnecting subscriber loops until cancelled. Testing it invites two mistakes:

- **Sleeping for the behaviour instead of awaiting it.** `delay(250)` then assert is a guess
  about a machine's speed; it passes locally and fails on a loaded runner. Poll for the
  condition under a generous ceiling instead — then the timeout bounds only genuine failure
  rather than defining the pass.
- **Removing the wait without bounding the loop.** Injecting `waitFor = {}` to skip a
  30-second backoff turns the retry into a busy spin. Combined with a `CoroutineScope` the
  test never cancels, the spin outlives its test and burns CPU for the remainder of the
  suite — five such tests took a CI runner past ten minutes.

The pair of rules: **await the behaviour, never sleep for it**, and **cancel every scope**
when the subject loops by design. Both belong in the test class's own documentation, because
the next person to add a case there will reach for exactly the same shortcuts.

A useful check before pushing: run the class three times and time the whole suite. A test
that leaks work shows up as a suite that gets slower, not as a test that fails.

## What supervision is actually for

Workers reported their own gaps honestly and usefully: #16's worker flagged that the
algorithm-run success path had no fixture, which turned out to be
[[concepts/assumption-vs-measurement]]'s mirror image and a latent silent-UNKNOWN bug. That
is the value — but honest reporting is not verification.

**Verify the load-bearing claim yourself, by breaking it.** On #18 the worker claimed
writes were serialized by dispatcher confinement; replacing `limitedParallelism(1)` with a
plain dispatcher made the concurrency tests fail by losing log lines, which proved the test
could actually catch the defect it was written for. A test that has never failed is a claim,
not evidence.
