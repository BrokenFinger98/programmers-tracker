---
type: concept
project: programmers-tracker
tags: [discipline, protocol, review-pattern, failed-attempts]
created: 2026-08-05
updated: 2026-08-05
sources: [raw/sessions/2026-08-05-design-review-and-stack-upgrade.md]
---

# Assumption vs Measurement — how our own claims became "facts"

## The failure mode

This project depends on an undocumented protocol, so every design sentence is either
**measured**, **assumed**, or **guessed**. The failure mode is not making an assumption — it
is an assumption *losing its label*: stated once in a design document, repeated in a
conversation, and thereafter cited as if it had been observed. By then nothing marks it as
unverified, and code gets built on it.

The 2026-08-05 adversarial review found four such claims. All four had been repeated
confidently; none were measured.

## The four caught claims

| Claim, stated as fact | What the evidence actually said |
|---|---|
| "`reject_subscription` is the measured signal for cookie expiry" | The protocol document never mentions `reject_subscription` **anywhere**. The design (§4.3) had built the entire expiry-detection mechanism on a message no one had ever seen. |
| "SQL terminates at `result_lesson_challenge`; algorithm terminates at `finish`" | Termination is an **(action × type)** matrix. SQL *submit* never sends `finish`, but SQL *run* does; algorithm *run* ends at `result`, or `error` on the error path. The half-truth would have hung every SQL run capture. |
| "Writes have no concurrency problem — there is only one writer" | Nothing in the design *created* that property. Single-writer is something you build, not something you inherit. |
| "5 verdicts cover every grading outcome" | The memory-limit message has **never been triggered**, so its string is unknown (protocol §14). A 5-way classifier silently misfiles it. |

## Why the protocol document survived the review and the design did not

`docs/programmers-protocol.md` marks its own uncertainty: §14 is an explicit list of
unverified items, bundle-extracted facts are labelled separately from measured ones, and
absences are stated ("SQL never sends `finish`"). Every wrong claim above came from the
design document or from conversation — layers where that labelling discipline was not
enforced.

**The lesson is not "read the protocol doc".** It is that the label must travel with the
claim. A design sentence that cites protocol behaviour should carry its section reference, so
that a claim with no citation is visibly suspicious rather than indistinguishable from a
measured one.

## The mirror image: evidence that never became a fixture

The four claims above were assumptions wearing the clothes of measurements. Issue #16
produced the opposite failure, and it is just as expensive.

Building the session assembler, a worker reported honestly that the algorithm **run**
success path had no fixture and was therefore untested end to end. That was true of the
fixtures — and false of the project: those exact frames had been captured live twice, in
the #6 verification and again after the Boot 4 upgrade in #10. The evidence existed in a
terminal log and a PR description, where no test could reach it.

The cost was not hypothetical. Without that capture, nothing showed that run testcases
identify themselves by 0-based `index` rather than `testcaseId`, so the mapper declined
them, a run grading collected **zero** testcases, and the session would have settled as
`UNKNOWN` — a silent wrong outcome on the most common user action there is.

**A measurement that is not a fixture is a measurement you do not have.** Live
verification output is evidence with a half-life: it proves something today and is
unreachable next week. Transcribing it into `src/test/resources/fixtures/` is what turns
an observation into something the build can defend.

## Settling a question instead of hedging it

Recording a caveat is honest, but it is not the same as answering. Issue #20 shows the
difference, on a question the design had carried unanswered since Phase 0: **does `run`
save the code?** If it does not, every `run` record silently attaches the *previous* code.

The first trial looked conclusive — baseline hash, unchanged after editing, changed after
pressing `run` — but it could not distinguish "`run` saved it" from "a debounced autosave
happened to fire in that window". The tempting move is to write the caveat down and move on.

The better move cost three minutes: edit again, then **wait without running**. The saved
code was still unchanged.

That converts a hedge into an elimination, and the reason is worth naming: **a debounce
short enough to explain the first trial would have fired during the second.** The second
trial was not more data — it was the trial that made the first one interpretable. When a
confound is a timing hypothesis, the experiment that kills it is usually to remove the
action and keep the time.

The payoff was not academic. With `run` confirmed as the saver, design §4.4's fallback —
the extension injecting itself into the page's main world to read the editor buffer —
became unnecessary, deleting the most invasive component of the planned sensor.

## Evidence is bytes, and the checkout platform can rewrite them

Issue #20's CI caught a third variant of the same theme. The measured page excerpt parsed
correctly on macOS and Linux and failed on Windows, because GitHub's Windows runners check
out with `core.autocrlf=true`: the raw newlines inside the captured `value` attribute became
CRLF, and the capture no longer said what Programmers had served.

Nothing was wrong with the parser. **The evidence had been edited in transit by the version
control system**, and every platform got a slightly different answer to "what did the server
send?".

The fix belongs to the evidence, not the code: `.gitattributes` marks
`src/test/resources/fixtures/**` as `-text`, so no checkout rewrites a capture. A capture is
bytes; anything that normalizes it is changing the measurement. A test now asserts the
fixture contains no CRLF, so a future removal of that rule fails by name rather than as a
confusing string mismatch.

This is also the clearest argument yet for the three-OS matrix: the bug is invisible on the
developer's machine by construction.

## A test that never runs looks exactly like a test that passes

The most expensive instance of this theme was self-inflicted. A Kotlin test method written
as an expression body — `fun \`x\`() = runBlocking { ... }` — returns whatever the last
expression returns. When that is not `Unit`, **JUnit does not run the method and does not
say so**: no error, no skip notice, no entry in the report.

Eight `ProblemPageCodeFetcher` tests reached `main` that way in #20/#21, through a green
three-OS CI run, having never executed once. Among them was the test asserting that the
session cookie never appears in a failure message. The PR claimed "failure paths tested".
That claim was false, and nothing in the pipeline could tell.

They pass now that they run — the production code was right all along. **That is what makes
this failure mode dangerous rather than merely embarrassing**: nothing was broken, so nothing
drew attention, and the same silence would have covered a test that genuinely failed.

The guard is structural rather than a habit: a Gradle verification task, run as
`finalizedBy` on `test`, fails when a class declaring `@Test` produces no result file. It
was negative-tested by reintroducing the defect. Counting assertions or trusting a green
build cannot detect an absence; only comparing *what should have run* against *what did* can.

## The counter-practice

- Cite the section inline when stating protocol behaviour; an uncited protocol claim is a
  review finding, not prose.
- When something cannot be measured (the MLE message, `reject_subscription`), **refuse to
  guess** — that is what `Unknown(type, raw)` does at the protocol layer and what the
  `UNKNOWN` outcome does at the verdict layer ([[decisions/2026-08-05-failure-taxonomy]]).
- Keep observations out of the fact document until reproduced. The ~30-minute silent socket
  close observed on 2026-08-05 was deliberately **not** written into the protocol doc,
  because its cause (server idle timeout? NAT? sleep?) was never established — see
  [[concepts/actioncable-broadcast-observation]].
- Trust a test only after seeing it fail once. Green is not evidence that a check exists —
  it is equally consistent with the check being absent.
- Before writing "we cannot exclude X", ask what one more trial would cost. If X is a
  timing hypothesis, the trial is usually "do nothing and wait".
- When a live run produces frames, transcribe them into a fixture in the same change —
  not "later". The protocol document's verification log records *that* it happened; the
  fixture is what keeps it testable.
- Confirmation is not validation: a wrong `challengeable_id` still returns
  `confirm_subscription` and still runs testcases (protocol §3). Success signals can lie
  about the thing you actually wanted to know — see [[concepts/verdict-classification]].
