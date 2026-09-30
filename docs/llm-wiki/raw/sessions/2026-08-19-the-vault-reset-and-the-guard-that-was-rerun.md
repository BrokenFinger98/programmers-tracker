# 2026-08-19 — the vault reset, and the guard that was rerun

Raw session record. Immutable (wiki schema §1). Recovered from the inbox snapshot of session
`b240e44e` on 2026-09-30; the segment runs from the evening of 08-18 KST to the morning of 08-19.
It had no raw session until now — the two PRs it produced (#322, #324) carried `Wiki-Skip`
trailers, which is how a segment goes unrecorded.

---

## The owner asked what they had to do; the answer was "nothing", with one exception

Every open item was closed except #319 (mine). The one thing only the owner could decide: the
vault held two problems, **both solved by me as tests**, with my elapsed times and attempt counts
in them. `goal.md` already said only problems solved from now on matter. The owner chose to wipe
and start from zero.

## Wiping found a trace that was not mine

Before deleting, the timers were checked. One entry was not from my tests: the owner had opened
**lesson 151136 (평균 일일 대여 요금 구하기, Lv1) on 08-17 20:49 for two minutes**, with no grading
frames — opened, not submitted. That timer is the owner's and was kept; only my two tests were
removed. (The same lesson is the one whose solve was lost to the expired cookie on 09-29 —
`2026-09-29-a-session-expiry-and-the-socket-that-looked-alive.md`.)

`refreshVault()` rewrites the whole tag map from the log at boot, so deleting the records was
enough for the tags to recover on their own: 83 tags back to `untouched`, pushed. The container
had to be stopped first for the lock.

## A tracked file the rule could not see

Cleaning up surfaced `ps-records.iml` **tracked** in the record repository's remote. #304's
`.idea/` rule does not cover a module file IntelliJ writes at the root. → #323 / PR #324, "ignore
the module file IntelliJ writes outside .idea/".

## #319 — the guard I had rerun until it passed

The CI comment pinning node said, verbatim: *"rerunning until it passes trains us to rerun on a
guard failure, which is how a guard stops meaning anything."* It had predicted my behaviour: on
2026-08-14 the Windows image came without dotnet, `CsharpRunnerExecutionTest` skipped all eight,
the guard fired — and the fix I applied was a re-run, which passed.

The measured answer: each of the seven runner languages was checked for what it depends on, and
the toolchains the execution proofs need are **declared** in the workflow rather than trusted to
the image. → PR #322, "declare the toolchains the execution proofs need". The lesson is the
comment's own sentence, now with a second instance behind it.
