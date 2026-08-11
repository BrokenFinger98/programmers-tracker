---
type: concept
project: programmers-tracker
tags: [discipline, testing, fixtures, protocol, failed-attempts]
created: 2026-08-11
updated: 2026-08-11
sources: [raw/sessions/2026-08-11-capture-defects-found-by-solving.md]
---

# Tests That Explain the Defect Instead of Catching It

## The failure mode

A test can fail in two ways. It can go red — which is the point of it — or it can **go green
around the wrong behaviour and leave a comment saying why that behaviour is correct.** The
second kind is worse than no test, because a missing test is visibly missing while this one
looks like coverage and reads like protocol knowledge.

[[concepts/assumption-vs-measurement]] is about claims that lose their label between a design
document and a conversation. This is the same disease one layer down, where it is much harder
to see: the assumption has been **written into an assertion**, and the assertion is passing.

On 2026-08-11 this pattern was found three separate times in one afternoon, and it is the
reason five of the nine capture-path defects fixed that day had survived a green suite
(`raw/sessions/2026-08-11-capture-defects-found-by-solving.md`).

## The three instances

**1. A KDoc that justified the bug.**

```kotlin
/**
 * The trailing error of `algorithm-run-error.jsonl` arrives after the first one already
 * terminated the stream — a measured frame belonging to no grading (protocol doc §7).
 */
```

There is no such thing as a frame belonging to no grading. It was the **second diagnostic of
the same failing run**: a run emits one `error` frame per compiler diagnostic and then a
`result`. `TerminationRule` was short-circuiting on the first one, and the test had been
written to describe what the code did, complete with a protocol-document citation that did not
say that ([[decisions/2026-08-11-a-failing-run-ends-at-its-result]]).

The citation is the dangerous part. A reference to §7 makes the sentence look measured. A
reviewer who checks §7 finds a section about error frames and moves on; the claim the comment
actually makes — *that a second one is an orphan* — is nowhere in it.

**2. A fixture README that stated our own bug as a fact about Programmers.**

> `algorithm-cached-result.jsonl` … **no verdict frames at all**

The capture had been truncated by the same short circuit before the verdict frames arrived. So
the fixture was **evidence of a defect, filed as evidence of a protocol** — and then reasoned
from. It was the basis for believing that a resubmitted problem reports its cached result and
stops, which is exactly backwards: it reports its cached result and then grades anyway, ending
on `finish` after eighteen testcases.

Fixtures are supposed to be the part of this project you cannot argue with. A truncated one
inverts that: it is an argument that cannot be checked, because it looks like a measurement.

**3. An assertion that spelled out the discarding rule.**

```kotlin
consume(capture, "algorithm-pass.jsonl")
consume(capture, "algorithm-pass.jsonl")
attached shouldHaveSize 1
```

"Feeding the same frames twice yields one record." Stated that way it sounds like idempotence,
which is a virtue. What it actually encodes is **byte-equality as grading identity** — and a
SQL submission carries no timing, so submitting the identical query twice produces byte-identical
frames and the second submission was silently dropped. The test was the defect, written down
and asserted ([[decisions/2026-08-11-a-grading-is-its-whole-session]]).

## Why it happens

Every instance came from the same sequence, and none of them from carelessness:

1. Behaviour is observed in the code (not in the protocol).
2. A test is written to pin the observed behaviour, so the suite stays green.
3. A rationale is supplied — because good practice says explain the non-obvious — and the only
   rationale available is the one that makes the current code correct.
4. The rationale now reads as domain knowledge, and step 1 is unrecoverable from it.

Step 3 is the trap, and it is set by a habit that is otherwise right. A comment explaining
*why* is normally the difference between a maintainable test and a mystery. Here it is what
converts "this is what our parser happens to do" into "this is what Programmers sends".

## How to tell the two apart

The question that separates them takes one sentence: **would this assertion still be true if
our code were deleted and rewritten from the protocol document?**

- *"A SQL submit terminates on `result_lesson_challenge`"* — yes. It is a claim about
  Programmers, checkable against §6, and stays true under any implementation.
- *"A second error frame belongs to no grading"* — no. It is only true of one `when` branch in
  one file, and it stops being true the moment that branch changes.

The second kind of sentence still belongs in a test, but as a **statement about our code**
("the resolver keeps only the first error"), never dressed as protocol. Written that way it
becomes suspicious on sight: a reader asks why, and there is no §7 to wave at.

## The counter-practice

- **A protocol claim in a test needs a citation that actually contains it.** Not a section
  about the same subject — the sentence. If the sentence is not there, the claim is ours, and
  it should say so.
- **A fixture is only evidence if the capture was complete.** A capture produced by the code
  path under test is circular; the truncation bug and the fixture that documented it were the
  same bug twice. `fixtures/README.md` describes shapes, and every shape claim it makes should
  be traceable to a live session in the protocol document's verification log.
- **Absence claims are the ones to distrust.** "No verdict frames", "no `finish`", "belongs to
  no grading" — all three instances were about something *not* being there, which is precisely
  what a truncating parser produces. A negative observed through our own pipeline is not an
  observation.
- **When a test's comment explains why odd behaviour is correct, that is a review finding.**
  Not necessarily a defect — but the comment is doing the work an assertion should be doing,
  and it is the shape all three of these had.
- **Drive the real thing.** Five of the nine defects were reachable only by writing code in the
  browser, running it, submitting it, and deliberately breaking the compile. The tests could
  not find them because the tests agreed with them.

That last point generalises past this project. A suite is a fixed point of whatever
understanding wrote it — it can only fail on the parts of the protocol you already modelled
correctly. The unmodelled parts are green by construction, and the only thing that disagrees
with them is the system itself.
