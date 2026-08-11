---
type: decision
project: programmers-tracker
tags: [protocol, verdicts, capture, measurement]
author: BrokenFinger98
created: 2026-08-11
updated: 2026-08-11
sources: [decisions/2026-08-05-failure-taxonomy, concepts/verdict-classification, concepts/assumption-vs-measurement]
---

# A failing run ends at its result, and a compile error is a verdict

Two defects from one live capture — lesson 181946, 2026-08-11, a Java solution that did not
compile. They are recorded together because neither is visible without the other: fix the
truncation alone and the record is still UNKNOWN; fix the verdict alone and half the grading
is still filed as orphans.

## Context

The run path emits **one error frame per diagnostic** and then a `result` (protocol doc §7):

```
{"action":"run","type":"error","index":0, …}
{"action":"run","type":"error","index":1, …}
{"action":"run","type":"result","passed":false, …}
```

`TerminationRule` said otherwise:

```kotlin
if (received == TerminalKind.ERROR) return true
```

directly above a matrix that already said `(RUN, ALGORITHM) -> RESULT`.

And `VerdictResolver` opened with `if (testcases.isEmpty()) return null` — before reading the
error text it had already been handed, and before reaching the `compilerDiagnostic` regex
sitting three lines below it.

## What was measured

The grading settled on its first `error`. Then the second `error` and the `result` arrived
0.3 s later, to a closed grading, and were filed under `orphans/` with the message *"its start
was missed"* — the one explanation that was definitely not true.

The record read `verdict null, outcome UNKNOWN, tc 0/0`, and the drift warning fired, because
`UnknownReason` knows one text and it is the cached result.

## Decision

**`error` ends a grading everywhere except `(RUN, ALGORITHM)`**, where measurement says a
`result` follows. Every other cell keeps the short circuit: an identical resubmission returns
a cached result and then errors within a second (§13.2), and a failing database run has never
been captured, so there is nothing to change it on.

**When no testcase ran, the verdict is read from the error text** — `COMPILE_ERROR` when it
carries a `javac` diagnostic, `RUNTIME_ERROR` otherwise — *unless* `UnknownReason` recognises
the text, in which case it stays UNKNOWN.

That exception is the whole difficulty. A cached result is **also** a terminal error frame
with no testcases, and reading its text as a failure would file a grading the learner never
failed as a RUNTIME_ERROR. `UnknownReason.matching` exists so the two paths consult one list.

## Accepted costs

- **A failing run now waits for a frame that may never come.** If Programmers ever ends a run
  at its error, the grading stays open until the silence deadline abandons it and lands as
  INCOMPLETE. That is the safe direction — an INCOMPLETE says what happened, where the old
  behaviour said UNKNOWN and threw the rest away — but it is slower and it is a real change.
- **`algorithm-run-error.jsonl` no longer terminates.** The fixture holds `start`, `error`,
  `error` and no `result`, so the capture it drives now records nothing at all. The test says
  so rather than pretending otherwise; the fixture wants a `result` frame the next time a
  compile failure is captured whole.
- **`UnknownReason.measuredText` is public now**, so a test can assert against the string the
  production path matches on instead of a copy that drifts. A wider surface for a narrower
  risk.
- **Classification by error text is still shape-matching.** `:\d+: error:` is javac's, and a
  language whose compiler formats differently lands as RUNTIME_ERROR. Measured for Java only.

## Outcome

A test had been pinning the defect as intended behaviour — *"the trailing error … a measured
frame belonging to no grading"*. It was not an orphan; it was the second diagnostic of the
same run. That is the part worth remembering: the capture had been split in half since the
run path was first written, and a test explained the halves rather than questioning them.

---

## Amended 2026-08-11 (#154): the exception was the rule

This ADR scoped the change to `(RUN, ALGORITHM)` because that was all that had been measured,
and kept the short circuit everywhere else on the strength of protocol §13.2.

Four days of measurement later, on a cached-result resubmit of lesson 120802:

```
start · error 같은 코드로… · test_group · testcase ×18 · result_lesson_challenge · finish
```

**A cached-result submit grades anyway.** The error is a notice, not an ending. Stopping there
recorded UNKNOWN and filed eighteen passing testcases as orphans — the same shape as the run
defect, on the path this ADR had left alone.

So `error` now terminates **nothing**, and the matrix is the only rule. Both measurements say
the frame the matrix already named was sitting at the end of the stream; the short circuit was
an inference from one sentence of documentation, and it was wrong on both paths it touched.

Two further things follow, and both are worth more than the fix:

- **`algorithm-cached-result.jsonl` was never the protocol.** It was a capture this bug
  truncated, and its README entry said "no verdict frames at all" as though that were a
  measured fact about Programmers. It is kept, relabelled as the half it is, and superseded
  by `algorithm-cached-then-graded.jsonl` — reassembled from the two halves of one grading,
  verified by their shared channel identifier.
- **The orphan warning named a cause, and the cause was wrong every time.** "Its start was
  missed" fired eleven times across two lessons on 2026-08-11, and not once was a start
  missed: every one was the tail of a grading closed too early. A diagnostic that confidently
  names the one thing that did not happen sends the next reader to the wrong place. It now
  reports what is known — no grading was open — and offers both explanations without choosing.

