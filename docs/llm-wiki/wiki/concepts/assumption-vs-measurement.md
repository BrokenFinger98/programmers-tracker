---
type: concept
project: programmers-tracker
tags: [discipline, protocol, review-pattern, failed-attempts]
created: 2026-08-05
updated: 2026-08-14
sources: [raw/sessions/2026-08-14-the-first-run-test-and-what-it-found.md, raw/sessions/2026-08-14-the-clean-slate.md, raw/sessions/2026-08-14-the-night-the-records-learned-the-question.md, raw/sessions/2026-08-13-the-tally-that-counted-runs.md, raw/sessions/2026-08-11-expiry-has-no-socket-signal.md, raw/sessions/2026-08-05-design-review-and-stack-upgrade.md, raw/sessions/2026-08-11-capture-defects-found-by-solving.md, raw/sessions/2026-08-05-capture-pipeline-built-end-to-end.md]
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
| "`reject_subscription` is the measured signal for cookie expiry" | The protocol document never mentions `reject_subscription` **anywhere**. The design (§4.3) had built the entire expiry-detection mechanism on a message no one had ever seen. **Measured 2026-08-11 (#175): it never arrives.** An unauthenticated subscription is confirmed in 0.49 s and pinged normally and receives nothing, so the socket carries no expiry signal at all — and by then a health state had been built on it, see below. |
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

## The classpath your tests run on is not the one your users get

Issue #23 found the sharpest version of this yet, and only by starting the server.

`POST /watch` returned **500 to everything** — including the paths whose whole purpose was
to return 401 and 400. The cause was `kotlin-reflect`: Spring reads Kotlin method parameters
through it, and it was on `testRuntimeClasspath` (pulled in transitively by
`spring-boot-starter-test`) but **not** on `runtimeClasspath`. Thirteen `@WebMvcTest` slice
tests exercised the error contract and passed, while every `@ExceptionHandler` in the running
application died with `ClassNotFoundException`.

No amount of additional testing *in that environment* could have found it. The tests were not
wrong and the code was not wrong; **the environment the tests ran in was not the environment
the code would run in**, and that difference was invisible from inside either one.

This is the concrete reason behind the constitution's rule that features whose essence is
external interaction are done only once they have actually been connected. It reads like
caution about protocols. It is really about classpaths, configuration, wiring, and everything
else a test harness quietly supplies on your behalf.

Two more defects surfaced in the same session for the same reason — a timer nobody started,
so every record carried a measured-looking `elapsedSec 0`, and a completeness flag that was
structurally false for the most common action. Both were invisible to a green suite of 422
tests. **Running it once found three defects that 422 tests could not.**

## Verifying a guard against a dirty workspace

The task written to catch silently-skipped tests was itself verified wrongly, twice.

The second time is the instructive one. `verifyEveryTestClassRan` compares the test classes
in the source tree against the result files on disk. It passed locally and failed in CI on
all three operating systems, reporting **every** class as never having run — because it had
no dependency on `test` and read an empty results directory. Locally it had read result files
left behind by an earlier run.

So the check "passed" by measuring **stale state**, which is the same failure it was written
to detect. A verification that can succeed without the thing it verifies having happened is
not a verification.

The general form: **a guard must be tested from the state it is meant to protect**, not from
whatever state the workspace happens to be in. For anything reading build output that means
`clean` and `--no-build-cache`; for anything reading a checkout it means a fresh clone or an
equivalent. "It passed on my machine" is a statement about a machine's history as much as
about the code — and history is exactly what a stale artifact preserves.

## Missing data must look missing

Every record this project writes today has an **empty `title`**: no catalog is wired, so
nothing knows the problem's name. Generating a README from those records forces a choice
that recurs everywhere data is derived — what to render for a field that has no value.

The tempting options are all quiet lies. A placeholder title (`Untitled`, `Problem 120804`)
reads like a name and will be copied into notes, search results and, eventually, a
statistic. An empty string renders as an empty heading, which looks like a rendering bug
rather than absent data. Both let a reader believe the field was populated.

The rule adopted: **omit the key entirely and fall back to an identifier that is obviously
an identifier.** The frontmatter simply has no `title`, and the heading is the lesson id.
A reader can tell at a glance that the name is unknown rather than blank, and a later
catalog fill-in changes the file visibly.

This is the same instinct as refusing to write `elapsedSec 0` for an unstarted timer, or a
verdict for an unrecognised failure message: **a value that looks measured is worse than no
value**, because only the second one prompts anyone to go and measure it.

## Read the failure, do not pattern-match it

A Windows-only CI failure on #41 showed `InvalidPathException` in the job log and nothing
else. That is enough to start guessing — and three plausible guesses (a colon in a URL, a
config key resolving wrongly, a temp file named with an instant) were all wrong.

The uploaded test report carried the actual message:

```
Illegal char <:> at index 16: C:\Users\RUNNERC:\Users\runneradmin1\AppData\Local\Temp\/...
```

Which names the cause outright: a Windows temp directory is an 8.3 short path containing a
tilde (`C:\Users\RUNNER~1\AppData\Local\Temp`), and home-directory expansion written as
`replaceFirst("~", home)` rewrites a tilde **anywhere** in the string.

Two lessons, and the second is the reusable one:

- **The job log truncates stack traces; the uploaded report does not.** `gh run download`
  costs one command and answers what several rounds of guessing could not. Reach for it
  first, not after.
- **The same one-line idiom had been copied to three call sites.** Fixing where CI pointed
  would have left two. A defect found in a copied line is a prompt to grep for the idiom, not
  just to patch the instance.

Third time the three-OS matrix has caught something invisible on the developer's machine —
after git rewriting a fixture's line endings and a guard reading stale results. All three
were environment differences rather than logic errors, which is exactly the class a single
machine cannot show you.

## When the assumption reaches the assertion

Everything above is about claims that lose their label while moving between documents and
conversations. 2026-08-11 found the terminal stage of that journey: three assumptions had
been **written into the test suite** — a KDoc citing a protocol section that does not contain
its claim, a fixture README stating our own truncation bug as a fact about Programmers, and
an assertion spelling out the rule that discarded a submission.

At that point the assumption is not merely unlabelled. It is *passing*, and a green suite is
the strongest label a claim can wear here. Five of that day's nine capture defects survived
the suite for exactly this reason, and were found only by driving the real system — a
compounding of the classpath lesson above, with the tests not merely blind to the defect but
agreeing with it. Full pattern and its counter-practice:
[[concepts/tests-that-explain-defects]].

## The worst case: a fix built on the unmeasured claim

Claim #1 above sat labelled and unmeasured for six days. On 2026-08-11 it was *built on*:
`SubscriptionHealth.REJECTED` fires on `reject_subscription`, and the badge state telling a user
to replace `.ps/session` was wired to it. Then it was measured.

An invalid session is **confirmed in 0.49 s and pinged for as long as you watch**. It is never
rejected. A ping is a frame, so the health reads `LIVE`, and the badge is green while every
grading is lost (`raw/sessions/2026-08-11-expiry-has-no-socket-signal.md`).

The part worth keeping is what the fix did to the *quality* of the wrong answer. Before it,
`/watch` said `started` unconditionally — obviously uninformative. After it, the same request
says `subscription: "live"`, which is a **reason to believe**. Building on an unmeasured claim
did not leave the confidence where it was; it raised it.

**A health check has to be built on a signal observed to differ between health and failure.**
Nothing in a code review can catch a check wired to a frame nobody has seen — only running it
against a broken credential can.

## Two views of one log disagreed, and only one had ever been asked

2026-08-13. Same server, same records, same moment
(raw/sessions/2026-08-13-the-tally-that-counted-runs.md):

```
list_problems(status=passed)  →  181952 … "attempts": 8
stats(groupBy=problem)        →  181952 … "count":   15
```

`stats` was counting runs as submissions. `stats(groupBy=verdict)` therefore reported 7 compile
errors and 2 runtime errors — **every one of them from pressing Run while writing code.** The
owner had made 11 submissions and passed 10. The MCP layer exists so the AI interprets and the
server does not; a reader of those numbers would have described a learner who cannot compile.

The rule was not missing. `ReviewQueue`, `SlowPasses`, the tag map and `CatalogBrowse` all test
`action == SUBMIT`; `SubmissionTally` counted whatever `RecordQuery.history()` handed it, which
is both. Its own KDoc said "counts submissions"; design §5.1 says a run is not an attempt, and
`ProblemReadme` had obeyed that since it was written.

**No test pinned it either way.** Every tally test used the fixture's default action, so the
calculator was never handed a run, and counting them was not a decision — it was what reading
`history()` happened to do. The suite stayed green after the fix, which says the same thing from
the other side.

This is the assumption-losing-its-label failure moved one level out: not a protocol claim nobody
measured, but **an invariant four call sites kept by hand and nobody stated once.** A rule kept
by convention in four places is enforced in none, and its fifth site is invisible until two
consumers of the same data are compared.

**Continuing the same comparison found a sixth site the same night.** `get_problem` published
`"submissionCount": 15` for the problem `list_problems` called 8 attempts, because the field was
the length of an array that holds runs too (#237). The array is right — a run is where the
compiler output comes from — and only the count was wrong, so it now answers `submissionCount`
and `runCount` both, in the two words `problems/<id>/README.md` had used all along.

Twice in two days is what settled the shape of the fix: the rule became
`SubmissionRecord.isSubmission()`, one method the counting sites ask, rather than a comparison
spelled out wherever it happens to be needed. **Two views of one thing should not need two
vocabularies**, and the surest way to keep them from drifting is to give them one sentence to
share rather than one convention to remember.

## Three refutations in one night, all of them mine

2026-08-14 produced the cleanest run of this pattern yet, because each claim was checked within an
hour of being made ([[sources/2026-08-14-the-night-the-records-learned-the-question]]).

| I asserted | The measurement | What the difference cost |
|---|---|---|
| `SubmissionRecord`'s 70 uncovered branches are generated `equals`/`hashCode` | 69 are in `<init>` — Kotlin compiles each default parameter into a bitmask test, and the class has 15 defaulted fields | It was in a GitHub issue before it was measured. Same conclusion, and the *mechanism* is what decides how to filter it |
| The runner package's 66% is depressed by C# execution tests skipping locally | CI's uploaded artifacts: 49 execution tests, **0 skipped**, and the package reads **575/862 on both machines** | The number was already right; execution proofs add zero branch coverage |
| The runners have no execution proof, so building one is the next work | Seven have existed since #84/#86, with the exact `assumeTrue` posture I was about to propose | A recommendation that would have had someone rediscover 49 passing tests |

All three share one shape: **an inference stated with the confidence of a measurement**, and each
took under five minutes to check. The second is the sharpest, because the fix that exposed it was
built *for* that purpose — the gate had been hiding the exempt package's number, and the first
thing the number did once visible was refute the hypothesis that asked for it.

The practical rule this leaves: when a claim explains *why* a number is what it is, the claim is
about the number and can be checked against it. `find`, a downloaded artifact and a method-level
counter each settled one of these; none needed an argument.

## Two more the same day, and one I reversed before it shipped

The afternoon of 2026-08-14 added two, and they differ from the three above in a way worth keeping
([[sources/2026-08-14-the-first-run-test-and-what-it-found]]).

**The one I caught myself, mid-implementation.** #316 needed a decision, and I recommended moving
the push that a pass triggers to after the source fetch — one push, tidy, and the owner approved
it. Reading the code to build it turned up `copiedRawPath`'s own comment: *"the verdict is
unrecoverable and the copy is not."* Moving the push makes the unrecoverable half wait on a
network fetch of the recoverable half, which is a milder form of the option I had just rejected on
exactly that ground. The push is now **added** rather than moved.

The claim was not refuted by a measurement — it was refuted by a sentence already written in the
repository, which I had cited approvingly one message earlier while arguing against a *different*
option. **A principle you can quote is not the same as one you have applied**, and the gap between
the two closed only because implementing it meant reading the file again.

**The one I could not settle from this side.** The #314 issue body stated that opening the vault in
Obsidian rewrites `dashboard.base`. The restored file then sat untouched for 75 minutes with the
vault open and Obsidian running. That is a failed reproduction, and the right move was to strike
the claim in the issue, separate what was still measured from what was not, and name the one step
this side cannot take — rendering the Base view, which needs the owner's application.

They did it, and the hash moved inside the minute to byte-for-byte the earlier output. **The
original diagnosis was right and its trigger was wrong**, and those are two claims, not one. The
cost of not separating them would have been a fix shipped against a mechanism nobody had
reproduced.

| I asserted | What happened |
|---|---|
| Moving the push after the fetch is the clean fix | Refuted by a comment in the repository, while implementing — the verdict must not wait on the source |
| Having the vault open rewrites the dashboard | Reproduction failed at 75 minutes; corrected in the issue, then confirmed with a narrower trigger the owner could produce |

**A failed reproduction is a result.** Reporting it and narrowing the claim is what let the real
trigger be found; carrying the original wording forward would have made the eventual confirmation
look like agreement with something that was never true.

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
- **Never wire a failure state to a frame that has not been observed.** If the failure signal
  cannot be produced on demand, the check is unverifiable by construction — say so where it is
  written rather than shipping it as working.
- Confirmation is not validation: a wrong `challengeable_id` still returns
  `confirm_subscription` and still runs testcases (protocol §3). Success signals can lie
  about the thing you actually wanted to know — see [[concepts/verdict-classification]].
- **When a fact is being inferred, ask who already knows it.** Records inferred algorithm-vs-SQL
  from `language` and `part` for the tool's whole life, while `ChannelKey.kind` — the value the
  server picks the channel by — sat one parameter away (#256). The wire value is `database`, not
  `sql`, so guessing the vocabulary instead of measuring it would have shipped a word the
  protocol never uses.
- **Ask two consumers of the same data the same question.** Where a rule is applied by hand at
  several call sites, the site that forgot it cannot be seen from inside — every test there
  agrees with the code, because the same author wrote both. Comparing two answers is what made
  `stats` and `list_problems` disagree out loud.
