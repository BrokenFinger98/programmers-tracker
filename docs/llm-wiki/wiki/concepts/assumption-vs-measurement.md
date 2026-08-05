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
- Confirmation is not validation: a wrong `challengeable_id` still returns
  `confirm_subscription` and still runs testcases (protocol §3). Success signals can lie
  about the thing you actually wanted to know — see [[concepts/verdict-classification]].
