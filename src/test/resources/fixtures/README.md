# Protocol fixtures — measured message captures

Each `.jsonl` file is a sequence of raw ActionCable wire frames (one JSON object per
line) transcribed from the measured captures recorded in
[`docs/programmers-protocol.md`](../../../../docs/programmers-protocol.md)
sections 4–8 and the section 15 verification log. Korean `msg` strings are kept
verbatim — failure classification depends on them (protocol doc section 7).

| Fixture | Source | Notes |
|---|---|---|
| `algorithm-pass.jsonl` | §5, §15 #6 — lesson 120804, 16/16, `finish` present | testcase frames are a representative subset (2 of 16) |
| `algorithm-wrong.jsonl` | §5, §15 #1 — lesson 120803, partial score `1.4` | wrong-answer testcase frame is the §5 measured example |
| `algorithm-timeout.jsonl` | §7, §15 #11 — lesson 120805, `실패 (시간 초과)`, `run_time`/`memory_size` null | |
| `algorithm-runtime.jsonl` | §7, §15 #10 — lesson 120810, `실패 (런타임 에러)` | |
| `algorithm-compile.jsonl` | §7, §15 #9 — lesson 120820, same `msg` as runtime error (indistinguishable on submit path) | |
| `sql-pass.jsonl` | §6, §15 #7 — lesson 131528, snake_case fields, **no `finish` frame** | |
| `sql-run.jsonl` | §6, §15 #8 — `returned_rows` double-encoded, `msg` explicitly null | |
| `algorithm-run-error.jsonl` | §7 run path, §15 #12–13 — HTML-escaped compiler output / stack trace | **truncated: `start · error · error`, no `result`** — see below |
| `algorithm-run-pass.jsonl` | **Our own live capture**, lesson 120804, 2026-08-04 and reproduced 2026-08-05 (issues #6, #10) | the only fixture not transcribed from the protocol doc — see below |
| `algorithm-cached-result.jsonl` | lesson 181952, 2026-08-07: `start` then `error` `같은 코드로 채점한 결과가 있습니다.` — **a truncated capture, not the whole protocol.** It was read as "no verdict frames at all"; in fact this code closed the stream at the error and lost the rest (#154) | kept as the capture it is, and as what a grading interrupted mid-flight looks like |
| `java-compile-error.jsonl` | lesson 181952, 2026-08-12 (#212): javac, **two diagnostics in one `error` frame**, ending `2 errors` | whole — `start · error · result` |
| `cpp-compile-error.jsonl` | lesson 181952, 2026-08-12: clang, `/solution0.cpp:8:15: error:` | whole |
| `c-compile-error.jsonl` | lesson 181952, 2026-08-12: clang, `/solution0.c:6:21: error:` | whole |
| `kotlin-compile-error.jsonl` | lesson 181952, 2026-08-12: kotlinc, `/Solution0.kt:3:15: error: syntax error:` | whole |
| `csharp-compile-error.jsonl` | lesson 181952, 2026-08-12: `/Solution0.cs(10,31): error CS1002:` — **the position is bracketed**, which is why a javac-shaped pattern missed it | whole |
| `javascript-compile-error.jsonl` | lesson 181952, 2026-08-12: node, `SyntaxError: missing ) after argument list` + stack | whole |
| `python-indentation-error.jsonl` | lesson 181952, 2026-08-12: `IndentationError: unexpected indent` | whole |
| `python-tab-error.jsonl` | lesson 181952, 2026-08-12: `TabError: inconsistent use of tabs and spaces in indentation` | whole |
| `kotlin-missing-main.jsonl` | lesson 181952, 2026-08-12: `main 메소드가 정의되지 않았습니다` — a top-level `fun main()` with no parameters. **Not a compile failure**, and the same message comes back whether the body is correct or not | whole |
| `algorithm-cached-then-graded.jsonl` | lesson 120802, 2026-08-11: the same path measured whole — `start · error · test_group · testcase ×18 · result_lesson_challenge · finish`. A cached-result submit **grades anyway** | reassembled from the two halves #154 split apart, both from one grading and verified to carry the same channel identifier; `surveyUrl`, `finishModalLink` and both ratings substituted (§7.3) |

### `algorithm-run-error.jsonl` — why it stays, and what it is not

Since [[decisions/2026-08-11-a-failing-run-ends-at-its-result]] a failing run terminates at
`result`, and this capture has none — so the capture it drives records nothing, and the ADR
listed that as an accepted cost until a compile failure was captured whole. **Nine such
captures now exist** (the `*-compile-error.jsonl` rows above, #212), so the debt is paid and
this file no longer has to stand in for a whole one.

It is kept because it is still the only capture of something the new ones do not show: an
`error` frame at `index: 1` carrying a **runtime stack trace**. What it does *not* show,
despite the ADR's wording, is one frame per compiler diagnostic — `java-compile-error.jsonl`
settles that, with two diagnostics in a single frame. The `index: 0` compile error and
`index: 1` stack trace here cannot both belong to one execution of one program, so the two
frames are of different provenance; the protocol doc cites §15 #12–13, two entries. Read it
as two shapes filed together, not as one session.

### `algorithm-run-pass.jsonl` — provenance

Every other fixture is transcribed from the protocol document. This one was captured by
this project's own client during the issue #6 live verification and reproduced byte-alike
after the Spring Boot 4 upgrade (#10), because the protocol document records the run
success *sequence* (§10) without a frame-level transcript of the terminal `result`.

It is the measured evidence for three things the fixtures otherwise could not test:

- the algorithm **run** path terminates at `result`, not `finish`
- run testcases identify themselves by **`index`** (0-based), not `testcaseId`
- testcases arrive **out of order** — `index 1` precedes `index 0` in this capture

## Scrubbing (development-rules §7.3)

- Ratings substituted: `oldUserRating`/`newUserRating` → `1000`/`1001`
- `surveyUrl` → `/custom_form_groups/0`, `finishModalLink` lesson ids → `0`
- `challengeable_id`/`testcaseId` values not documented for a lesson are substituted
  with plausible placeholders (documented ids — 14643, 2778, 154893/154894, 5437/5438 — kept)
- Lesson 181952's `challengeable_id` **17582 is kept measured** rather than substituted.
  `algorithm-cached-result.jsonl` already carries it for the same lesson, and two different
  ids for one lesson across fixtures would read as an id that changes — a worse lie than the
  one the substitution rule guards against
- No emails, user ids, or session cookies appear in any fixture

## What is verbatim and what is substituted

The distinction matters because these two look alike and are not: **one is data we are only
borrowing, the other is what the parser matches on.** Someone tidying up later will want to
"fix" a substituted value back to what the site actually sent — this section exists to say
which values that would break.

### Substituted — Programmers' example data (#62)

The `testcases[].input` and `.output` in the `run` captures. They are the site's example
values, and holding them in a public repository buys nothing: the tests exercise the
**shape** — two cases, a comma-joined argument string, a scalar expected value — and any
values with that shape do it equally well.

**Preserve the shape exactly when touching these.** The shape *is* the measurement, and it
carries facts the parser depends on: comma-joined arguments, quoting, nested brackets, and
the raw newline inside a quoted string that strict JSON rejects (protocol §7.1).

### Verbatim — protocol values (do not substitute)

The Korean result strings: `실패 (시간 초과)`, `실패 (런타임 에러)`, `테스트를 통과하였습니다.`
and the rest.

These are **functional**, not content. Verdict classification matches on them (protocol §7),
so altering one makes the fixture test a protocol that does not exist. That is the failure
this whole file exists to prevent — a test that passes against an imagined wire format.

Frame types, field names, ordering and null-ness are verbatim for the same reason.
