---
type: concept
project: programmers-tracker
tags: [orchestration, workflow, testing, debugging-pattern]
created: 2026-08-05
updated: 2026-08-05
sources: [raw/sessions/2026-08-05-design-review-and-stack-upgrade.md]
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
