# 2026-08-10~11 — capture-path defects found by actually solving problems

Raw session record. Immutable (wiki schema §1).

The half of this session that matters started when the owner asked the assistant to drive
Chrome itself: write code, run it, submit it, cause a compile error. Nine capture-path defects
followed. **Five of them were invisible to the test suite** and only appeared because a real
grading was watched end to end.

---

## What was already there before any of it

The record repository had been saying so for five days and nobody read it: **every problem in
it had exactly one submit.** That reads like someone who passes first try. It was #149.

---

## The nine, in the order they were found

| # | What was wrong | How it surfaced |
|---|---|---|
| 149 | The capture key was `digest(lessonId, action, terminalFrame)`, and an algorithm submit terminates on `finish` — a frame carrying no verdict, no score, no timing. The key was therefore **a constant per problem**, and every submit after the first collided with it | Owner solved 181947; the passing submit derived the key of a WRONG submit from five days earlier and was dropped |
| 151 | `VerdictResolver` opened with `if (testcases.isEmpty()) return null`, before reading the error text it had already been handed and before reaching the `compilerDiagnostic` regex three lines below. A compile failure runs nothing, so it reports no testcases | A Java compile error recorded as UNKNOWN, tripping the protocol-drift warning |
| 152 | `TerminationRule` short-circuited on `ERROR`, directly beneath a matrix saying `(RUN, ALGORITHM) -> RESULT`. The run path emits one error frame per diagnostic and then a result | Two-diagnostic compile failure split in half; the rest filed as orphans "whose start was missed" |
| 154 | The same short circuit on the submit path. A cached-result submit reports its error **and then grades anyway** | Resubmit of 120802: `start · error · test_group · testcase ×18 · result_lesson_challenge · finish`. Eighteen passing testcases orphaned, record UNKNOWN |
| 156 | Nothing told the user whether a grading became a record | #154 hid for twenty minutes: the page announced a pass, the server recorded UNKNOWN, and nothing on screen disagreed with anything else |
| 157 | Raw session names were `<stamp>-<lesson>.jsonl` and nothing more | Two channels opened a grading in the same millisecond, wrote into one file (`start ×2 · error ×4 · result ×2`), and retirement destroyed one |
| 158 | Two channels for one problem both received the broadcast, and each frame carries **its own subscription's identifier** — so the byte streams differ and both key differently | One Python run produced two records, one labelled `java` carrying Python's traceback |
| 159 | The capture key was consulted on the live path, where matching bytes do not mean the same grading | Same SQL query submitted twice; SQL frames carry no `run_time` or `memory_size`, so the second was byte-identical and dropped |
| 160 | `compilerDiagnostic` was `:\d+: error:` — **javac's** shape, unlabelled | A Python `SyntaxError: expected ':'` recorded as RUNTIME_ERROR |

---

## The thing that happened three times

Not a defect. A pattern, and it is why five of the nine survived a green test suite.

**1. A test explained the defect instead of questioning it.**

```kotlin
/**
 * The trailing error of `algorithm-run-error.jsonl` arrives after the first one already
 * terminated the stream — a measured frame belonging to no grading (protocol doc §7).
 */
```

It was not an orphan. It was the second diagnostic of the same run. The test had a reason for
the wrong behaviour and the reason sounded like protocol knowledge.

**2. A fixture's README stated our own mistake as a fact about Programmers.**

> `algorithm-cached-result.jsonl` … **no verdict frames at all**

There were verdict frames. The capture had been truncated by #154 before they arrived. The
fixture was evidence of a bug, filed as evidence of a protocol.

**3. A test asserted the exact reading that discarded a submission.**

```kotlin
consume(capture, "algorithm-pass.jsonl")
consume(capture, "algorithm-pass.jsonl")
attached shouldHaveSize 1
```

"The same frames twice means one grading" — which is what dropped the second SQL submit.

---

## Measured protocol facts established

All from live captures, 2026-08-10~11.

- An algorithm submit's `finish` frame is `{"action":"submit","type":"finish"}` beside the
  channel identifier. **Byte-identical across gradings of one problem.**
- A failing run emits **one `error` frame per diagnostic**, then `result`.
- A cached-result submit emits its `error` and then the full grading, ending on `finish`.
- SQL sends **no per-case timing**: `start · testcase · result_lesson_challenge`, everything in
  them deterministic for a given query. Confirms protocol §6 and explains #159.
- SQL submit terminates on `result_lesson_challenge`, never `finish`. Confirms the matrix.
- Each subscription receives the broadcast with **its own identifier** stamped in — so the
  identifier is delivery metadata, not grading data.

---

## The earlier half of the session

| # | |
|---|---|
| 123 | The English-only guard had never executed its search on Linux. `[가-힣]` is a locale-collated range: `C` matched 1006 English comments, `C.UTF-8` died `Invalid collation character` (exit 128), and `\|\| true` made the crash indistinguishable from a clean tree |
| 126 | State resolved against the working directory while design §5.1, four factories and a KDoc all said the record repository. Moving it in required the server to add `.ps/` to the user's `.gitignore`, or `git add --all` would commit the capture history twice |
| 132 | `review_queue`. Settled a standing conflict: design §6.4 asks the server to compute a confidence, CLAUDE.md forbids rule-based analyzers. Boundary — **diagnosis is a claim about the learner, scheduling is a claim about a date** |
| 134 | `slow_passes`. Same boundary applied a second time; no baseline invented because a two-pass record set has no peers |
| 136 | `hintLevel` was being served over MCP as `0` on every submission — a measurement never taken |
| 142 | Korean twins for five user-facing pages, with drift as a build failure. The reversal only held because the objection that refused it once ("guaranteed drift") became a guard on a blob hash |

---

## A finding about this wiki

`raw/sessions/` was last written **2026-08-05**. `log.md` records **33 ingests**.

The practice had drifted from running the skill to writing an ADR inline and appending a log
line. The log entry is the visible part of the ritual and it kept being copied; the raw save is
the part with no immediate consumer and it stopped. Six days of heavy work left no raw layer —
the layer the schema calls the source of truth (§1).

The assistant did the same thing six times in this session before being asked whether it had
run the skill.

Worse, two ADRs **cite raw sessions that were never written**:

| ADR | `sources:` entry | Exists |
|---|---|---|
| `2026-08-08-run-raw-sessions` | `raw/sessions/2026-08-07-adversarial-review.md` | no, never committed |
| `2026-08-10-sensor-observations` | `raw/sessions/2026-08-10-sensor-verified.md` | no, never committed |

Citing a file makes a page look traceable whether or not the file is there, so the drift was
invisible from inside the wiki — the same shape as the three test findings above. Both entries
were removed rather than back-filled: a raw session reconstructed from the wiki page that
supposedly sourced it is not a source. `scripts/guards.sh` §6 now fails the build on a
`sources:` path that does not resolve.

---

## Method note

The owner's instruction — *"use Chrome yourself, write code, run, submit, cause a compile
error"* — produced five defects the suite could not. Two mistakes of the assistant's own are
part of the record: pressing Run and then `cmd+a` while focus had left the editor, which sent
two more runs of broken code to the owner's account; and committing #149 to `main` before
creating its branch (moved, never pushed).
