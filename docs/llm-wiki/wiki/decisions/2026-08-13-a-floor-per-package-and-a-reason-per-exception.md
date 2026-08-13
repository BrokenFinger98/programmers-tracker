---
type: decision
project: programmers-tracker
tags: [ci, testing, coverage, guards]
author: BrokenFinger98
created: 2026-08-13
updated: 2026-08-13
sources: [raw/sessions/2026-08-13-the-map-becomes-a-workspace.md]
---

# A branch-coverage floor for every package, and a written reason for every exception

## Context

The owner asked why the `coverage report` CI job only publishes a report and enforces nothing.
It turned out a threshold did exist — `verifyCalculatorCoverage`, 95% branch coverage on
`domain/calc`, running in the `gates` job and failing the build. It passed at 96%.

It was also reading 13% of what it named. `branchCoverageOf` looked the package up by exact name:

```kotlin
Regex("""<package name="$packagePath">(.*?)</package>""", …)
```

Kover emits `domain/calc` and `domain/calc/runner` as **two** `<package>` elements, so the gate
measured 132 branches and never saw the 862 in the same directory — 287 of them uncovered, the
largest such pool in the repository, inside the one place the project told itself was held to
95%. The comment on that function even argued for the shape that caused it: *"Reads the package's
own counter rather than summing classes, so a class added later is included without touching
this."* True of classes, false of sub-packages.

## Options considered

1. **A single project-wide threshold.** Rejected, and the measurement is why. Branch coverage read
   75% overall, and the largest contributor was not missing tests: Kotlin compiles every default
   parameter value into a bitmask test, so `SubmissionRecord`'s 15 defaulted fields emitted 69
   branches inside `<init>` that no call site takes both ways. `domain` measured 100% line and 57%
   branch almost entirely from that. A global number would have measured how many optional fields
   the data classes have. (An earlier draft of this analysis blamed generated
   `equals`/`hashCode` — that was inferred, and measuring at method level showed it wrong.)
2. **Ratchet each package at whatever it reads today.** Rejected: it prevents regression and
   asserts nothing, and every number in it would be an accident rather than a judgement.
3. **One floor for every package, overrides stated with reasons.** Chosen.

## Decision

`verifyBranchCoverage` replaces `verifyCalculatorCoverage`. Three properties:

- **It reads every package in the report rather than names it remembers.** Iterating what is
  there, instead of looking up what we thought was there, is what makes the original defect
  structurally impossible rather than something a reader has to notice.
- **It counts only hand-written members.** `<init>`, `equals`, `hashCode`, `copy`, `toString` are
  excluded — not as a concession, but because they are not code anyone wrote, and no test should
  ever be authored to move that number. Excluding runners and generated members, the tree measures
  **85% across 1,733 branches**.
- **Every deviation is named with its reason, and a name that matches nothing fails the build.**
  A rename must not quietly turn an exemption into a free pass.

Floors as set: 80% general, 95% `domain/calc`, 65% `adapter/config`, and `domain/calc/runner`
exempt.

## Rationale

The two deviations are different in kind and both are arguments rather than numbers.

**`domain/calc/runner` is exempt** because the 95% argument does not transfer to it. The
calculators are held high because they decide verdicts and a missed branch there is wrong data;
the runners generate scaffolding files and decide nothing. Holding them to 95% by accident of
directory layout would have been a number nobody chose — which is precisely what had been
happening in reverse.

**`adapter/config` sits at 65%** because what remains below the general floor is bean factories'
`?:` defaults and one private suspend helper, whose only honest test would assert Spring's own
wiring. The context-load test and the running server cover that. The two classes in the package
that are *not* configuration — `BackupSchedule` and `BuildIdentity` — were unit-tested here
instead, taking it from 36% to 65%; moving them out of the package would let the floor rise, and
that is the follow-up rather than a reason to skip them.

**Where the floor was reachable, tests were written rather than the floor lowered.**
`adapter/catalog` 23%→80%, `adapter/cable` 77%→86%, `adapter/git` 78%→81%. All of them turned out
to be absence paths this project claims to care about: a lesson the shipped catalog does not have,
a channel nobody subscribed to, a throwable with no message, a GitHub answer that is neither
"created" nor "already exists".

One production change came out of it. `ClasspathProblemCatalog.resource` chained safe calls onto
`bufferedReader()` and `use`, neither of which can return null, so it carried two branches no test
could ever reach. Rewritten as an early return — [[concepts/assumption-vs-measurement]] in
miniature: an unreachable branch inside a coverage floor is a number nobody can move honestly.

**The gate was made to fail before it was trusted.** Raising the general floor to 90 named four
packages; renaming the exempt package to one that does not exist produced *"a rule that matches
nothing rules nothing and hides that it does."* Both reverted. This is
[[decisions/2026-08-10-guards-must-prove-they-ran]] applied to the guard that had just been caught
not running on most of what it named.

## Accepted costs

- **65% is a real number that a reader will want to argue with**, and it should be argued with —
  it is written down for that, along with the move that would raise it.
- **`domain/calc/runner`'s 287 uncovered branches are still uncovered.** The exemption records
  that this is a decision, not an oversight. Closing it properly means compiling and running the
  generated files for real; the toolchains for all seven languages already exist on the
  GitHub `ubuntu-latest` image, so it needs no Testcontainers, only the work.
- **Excluding generated members hides a real class of bug**, in principle: a hand-written
  `equals` would go unmeasured. This project has none, and the trade is worth it against a number
  driven by field counts.
- **A per-package floor is gameable** by splitting a package. Nothing here prevents that; it would
  show up in review as a package that exists for no reason.

## Outcome

Implemented in #276 (issue #272). The gate is green at 80/95/65 with one exemption, and its two
failure modes are demonstrated above rather than assumed.
