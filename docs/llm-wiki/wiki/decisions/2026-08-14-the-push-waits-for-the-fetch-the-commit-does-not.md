---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [git, durability, capture-pipeline, record-keeping]
created: 2026-08-14
updated: 2026-08-14
sources: [raw/sessions/2026-08-14-the-warnings-and-what-was-under-them.md]
---

# A pass pushes twice: the verdict immediately, the solution when the fetch returns

`RecordWriter.attached` reconciles and pushes after `CodeAttachment` has written the files a
network fetch had to complete first. The **existing** push, on the scoped verdict commit, stays
exactly where it is.

## Context

Measured on the first-run test, 2026-08-14. Lesson 120811 was solved from a blank vault and the
commit read

```
504da90 [Lv0] 중앙값 구하기 — PASS (9/9, attempt 1, 3m57s)
```

while `git show --stat` said it held two files: `log/submissions.jsonl` and
`attempts/001.raw.jsonl`. `Solution.java`, `attempts/001.java`, `examples.json`, `statement.md`,
the problem `README.md`, the index and both tag notes were still untracked when `pushOnPass` fired.

Not a defect in the commit — the scope is deliberate:

```kotlin
private fun pathsOf(record: SubmissionRecord): List<Path> =
    listOfNotNull(submissionLog, record.rawPath?.let { recordRoot.resolve(it) })
```

`RecordWriter` commits the moment a grading settles. `CodeAttachment` runs after, because it has
to fetch the source from Programmers first. `commitScoped`'s own KDoc already says those writes
*"ride along with the next submit or the next reconciliation"* — riding along was known. What it
did not account for is that **a pass is what triggers the push**, so the one moment the design
promises an off-machine copy was the moment that copy had the least in it. Reconciliation is the
daily backup (`tracker.backup.at`, default 23:00), so the gap was up to ~24 hours.

## Options considered

**A. Commit everything in one scope.** The submission commit waits for `CodeAttachment`.

**B. Move the push after the attachment.** One push per pass, carrying both commits.

**C. Add a second commit-and-push after the attachment.** Two pushes per pass.

**D. Accept, and fix the promise** — say in design §4.6 and `docs/bootstrap.md` that a pass sends
up the verdict and the frames, and the derived files follow at the daily backup.

## Decision

**C.**

## Rationale

**A inverts the repository's own priority.** `copiedRawPath`'s KDoc states it plainly — *"the
verdict is unrecoverable and the copy is not"* — and the commit order exists because of that. A
would make the unrecoverable half wait on the recoverable one. Worse, `CodeFetch` has real
terminal outcomes (`Unauthenticated`, `RateLimited`, and the `DEFERRED` path), so under A an
expired session does not delay the verdict commit, it prevents it.

**B was recommended first, and reversed on reading the code.** It is A's mistake in miniature: the
verdict's off-machine copy would wait on a network fetch. The tidiness of one push is not worth
buying with the thing A was rejected for.

**C keeps both halves at their right latency.** The verdict reaches the remote immediately, even
if the fetch hangs. The solution, the statement and the page follow seconds later instead of at
23:00.

**The deferred case needs no branch, which is why C is also the smallest change.**
`GitSync.reconcile` is documented idempotent and a no-op on a clean tree, so an attachment that
wrote nothing finds nothing to add and the second push is a no-op — degrading to precisely the old
behaviour. `RecordWriter.attached` therefore never asks how the attachment went; `ChannelCapture`
calls it whether the attach threw or not.

C also changes no port contract, so the six push tests in `CommandLineGitSyncTest` keep testing
what they were written for.

## Accepted costs

- **Two pushes per pass.** ~1 s of network each, and the second is a no-op when there is nothing
  to add. The alternative buys one push with a worse failure mode.
- **A `chore: reconcile uncommitted records` commit per solved problem** rather than one nightly
  commit covering several. Roughly doubles the commit count in a solving session. The history is
  more accurate for it — a problem's files land with that problem — but the message still says
  `chore: reconcile`, which under-describes what it now carries. Naming that commit after its
  problem needs a new port operation and is deliberately not in this change.
- **A non-passing submit still waits for 23:00.** The pass remains the trigger, unchanged; this
  decision narrows what "pushed on pass" means, it does not widen when pushes happen.

## Outcome

Implemented in #316. Asserted at the remote rather than at the local repo, because the remote is
where the defect was measured — `GitWorkspace.filesAtHead(at = remote)` reads the pushed tree.

**The first run of that assertion failed for a reason that was not the code.** The bare remote
kept its own git config, so `ls-tree` came back with every Korean problem directory escaped
(`problems/120804-\353\221\220…`) while the working repository — which has set
`core.quotePath=false` since it was written, with a comment explaining this exact trap — did not.
The fixture was one repository short. Fixed there, so any later test that reads paths off a remote
is immune.
