---
type: source
project: programmers-tracker
tags: [subagents, workflow, git, ci, windows, measurement, live-verification]
created: 2026-10-10
updated: 2026-10-10
sources: [raw/sessions/2026-10-10-the-limit-the-load-and-the-last-five.md]
---

# 2026-10-09 / 2026-10-10 session summary — the limit, the load, and the last five PRs

## Key claims

1. **A usage limit is an interruption with state.** Three agents stopped mid-task: one mid-rebase, one
   mid-issue, one critic before it began. On resume the coordinator checked CI and every worktree before
   acting. Each agent was resumed by message with where it had stopped. A merge call made after the
   resume came back with nothing that parsed, so the PR was read again: it was still open, and a second
   call merged it ([[concepts/orchestrated-implementation]]).
2. **Machine load looked like agent failure.**
   - macOS's `XprotectService` ran at 773% CPU beside 33 Spotlight workers. Load averages of 64–82 made
     every process spawn take minutes.
   - Agents tripped the 600 s stream watchdog three times.
   - A push was killed at the 30-minute background limit, after it had in fact succeeded.

   The coordinator waited on a load monitor and changed one rule: long Gradle runs go in the background,
   one at a time.
3. **#374 closed the check-then-write race.** The gate critic swapped `.ps` for a link 100 ways, and
   nothing was written outside the directory checked. Descriptors stayed flat over 10,000 appends
   ([[decisions/2026-10-08-state-is-written-in-the-directory-it-checked]]).
4. **Two assumptions about git, found by machines other than this one**
   ([[decisions/2026-10-08-each-gate-searches-what-its-destination-lacks]]).
   - `insteadOf` is a string-prefix match, so Windows tests that built rules with backslash paths
     rewrote nothing.
   - `rev-list --stdin` takes no options before git 2.42. #405's `--not` line refused every commit and
     push on Debian 12's git 2.39.5.
5. **A Kotlin overload, found by the Windows leg.** `List + Path` adds the path's name parts, because a
   `Path` is an `Iterable<Path>`. One test deleted `Users`. #407's guard now covers every regenerated
   file, and only an unsupported atomic move falls back
   ([[decisions/2026-10-08-one-replace-and-no-crash-debris]]).
6. **The final live check, 23:41 on main `794c37f`.**
   - The boot was healthy, with no WARN or ERROR, and no handle-fallback line.
   - The 36 MCP answers were byte-identical to `41f713e`.
   - The 255 record files were byte-identical, except a `.gitignore` that gained #386's rule. The boot's
     reconcile committed it alone, and the modes were unchanged
     ([[decisions/2026-10-08-no-writer-follows-a-link]]).
   - #365 answered live as designed ([[decisions/2026-10-07-exam-prep-asks-in-the-open]]).
7. **The tally.** 24 PRs closed 25 issues in about 19 working hours. 17 of those issues had been filed
   during the window, from reviews and implementers' reports.

## Pages this source updated

The pages linked above. #360's decision
([[decisions/2026-10-08-reconcile-never-stages-the-state-directory]]) also records what the final
check says about its commit gate.
