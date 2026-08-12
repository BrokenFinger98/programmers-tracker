---
type: concept
project: programmers-tracker
tags: [discipline, testing, fixtures, protocol, failed-attempts]
created: 2026-08-11
updated: 2026-08-13
sources: [raw/sessions/2026-08-11-capture-defects-found-by-solving.md, raw/sessions/2026-08-07-adversarial-review.md, raw/sessions/2026-08-11-backfilling-the-raw-layer.md, raw/sessions/2026-08-12-the-improvement-loop-turns-inward.md, raw/sessions/2026-08-12-clean-slate-verification.md, raw/sessions/2026-08-13-the-map-that-linked-to-nothing.md]
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

## It was named four days earlier and not extracted

The 2026-08-11 finding was not the first sighting. On 2026-08-07 an adversarial reviewer, having
found four CRITICAL defects in the capture pipeline, closed its verdict with this
(`raw/sessions/2026-08-07-adversarial-review.md`):

> each currently has a test that walks past the defect without asserting on it

Its worked example is the sharpest one in the record. The ping never reset the silence deadline,
so an idle channel reconnected every fifteen seconds forever — and the reason it survived was
that `CableChannelSubscriberTest` stubbed the client with a hand-built flow, **bypassing the
layer that swallowed the ping**. One of its tests fed an empty flow and asserted the reconnect
*as desired behaviour*. That is the idle case, pinned as correct.

The sentence was read, the four defects were fixed, and the pattern was not written down.
Four days later it cost five more.

That is worth more than the pattern itself: **a finding stated inside a fix is not recorded.**
The fix closes the issue and the sentence goes with it. Whatever generalises has to be lifted
out deliberately, into a page that outlives the branch — which is the entire argument for the
wiki having a concepts layer at all.

## Two defects can protect each other

2026-08-12 produced a variant worth naming separately, because no amount of reading would have
caught it.

`SubmissionRecord.score` carried this KDoc since it was written:

> Null for every database grading — the SQL path reports no score (protocol doc §6).

It is **wrong**. The measured `sql-pass.jsonl` in this repository carries `userScore` and
`perfectScore`, and so does §6's own example. What SQL never sends is the per-category `scores`
array and the rating — which is exactly what dev rules §2.2 says, one document over.

It survived because of a *second* defect: `GradingFrameFacts` had no field for a score, so the
value never crossed the protocol boundary and **every** record was `score: null` — SQL and
algorithm alike. The wrong explanation described the right observation. Any test asserting "a SQL
record has no score" passed, and would have passed forever.

Neither defect is visible from the other's side:

- reading the KDoc against the records confirms it
- reading the mapper shows a field nothing fills, which looks like an unimplemented feature rather
  than a contradiction
- and the fixtures *do* populate a score, so the object mother agrees with the KDoc's implication
  rather than with production

What broke it was **writing the test from the documentation and letting it fail**. The SQL
assertion was written from that KDoc, ran against a measured capture, and disagreed. Had it been
written from the fixture — the more natural thing, since the fixture was open on the next screen —
it would have passed and taught nothing.

**A test written from the documentation is a test of the documentation.** That is usually a
weakness. Here it was the only thing in the tree capable of noticing.

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

## A true sentence can pin a defect just as well as a false one

2026-08-13, and this instance is worth keeping because nothing in it was wrong.

The tag map writes `tags/<tag>.md`, slugging the name so `binary_search` becomes
`binary-search.md`. A test pinned that:

> `a tag the filesystem would not take keeps its spelling in the field`

**Every word of it is correct.** The `tag:` frontmatter field is the datum and must stay
verbatim; the file name is a path and is slugged; they differ on purpose. The spec said the same
thing in the same words.

And 43 of the catalog's 83 tags shipped unlinkable, because both writers built the wikilink from
the tag. A link is a path. The test asserted that the two spellings differ and **never asked
which one a link needs** — so code, test and spec all agreed with each other, and none of them
with the filesystem.

The defect was latent, and saying so is part of the record: the live vault held 4 links, none to
a slugged tag, so nothing on disk was broken. The next change would have emitted 510 tag→tag
links, 178 of them dangling. A test that agrees with the rule fails silently until the rule is
exercised at scale, which is exactly when a wrong map is hardest to distinguish from a sparse
one.

The earlier instances on this page are wrong explanations of right observations. This one is a
**right explanation of an incomplete observation**, which is harder to see: there is no false
sentence to catch, and a reviewer checking the claim finds it true.

What separates them is the question asked. *"Do the two names differ?"* is a restatement of the
rule, and a test that restates a rule agrees with whatever the rule currently is. *"Does this
link name a file that exists?"* is a question about the world, and it is the one that failed:

```kotlin
val written = Files.list(root.resolve("tags")).use { ... }
links.shouldNotBeEmpty()
written shouldContainAll links
```

So the counter-practice gains a line. **Assert against the artifact, not against the rule that
produced it.** A rule and its restatement cannot disagree; a rule and the filesystem can.

Recorded also because of who wrote it: this page was cited twice on the day the defect was
written, by the author of the defect. Knowing the pattern is not protection from it — only
asking the outside question is.
