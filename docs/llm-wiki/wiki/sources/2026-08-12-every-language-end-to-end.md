---
type: source
project: programmers-tracker
tags: [protocol, verdicts, languages, measurement, fixtures, failed-attempts]
created: 2026-08-12
updated: 2026-08-12
sources: [raw/sessions/2026-08-12-every-language-end-to-end.md]
---

# 2026-08-12 session summary — every supported language, driven end to end

## Key claims

1. Seven languages in `GENERATORS`, **two patterns** in `compilerDiagnostics`, and nothing
   recording which languages that left uncovered. Found by counting two lists, not by a test.
2. The prediction was four uncovered languages. The measurement said **one**: C, C++, Kotlin
   and JavaScript were already classified correctly **by coincidence** — clang and kotlinc add
   a column (`:8:15: error:`) that the javac pattern matches on its tail, and node prints
   `SyntaxError:`, which the python pattern catches.
3. **C# was the genuine miss**: `Solution0.cs(10,31): error CS1002:` brackets its position, so
   no colon-digit-colon appears. Every C# compile failure was a RUNTIME_ERROR in the record.
4. Python's `IndentationError` and `TabError` print their own names, so `SyntaxError:` missed
   both — a gap the KDoc had *stated* and left unowned since #160.
5. All seven wire language strings match their generator keys and all eight runner files were
   written — assumed since #37, measured now. A mismatch would have produced **no runner and
   no message**.
6. **A confound was caught by one extra run.** Kotlin's first capture carried no compiler text,
   only a message about a missing main method. The correct code returned the identical message:
   the editor template is `fun main(args: Array<String>)` and the scaffold under test was mine.
   A negative result observed through your own setup is not an observation.
7. **A wrong reading, announced too early**: "every record is written twice" came from counting
   capture keys and reading a truncated dump. The second line per key is the code-attachment
   correction, and `RecordHistory` collapses it.
8. **An ADR quantifier broke.** Two algorithm gradings collided on capture key, which
   `a-grading-is-its-whole-session` calls vanishingly unlikely outside SQL — because a failure
   that never reaches a testcase reports no timing either. No behaviour change; the live path
   stopped consulting the index at #159.
9. Nine whole captures (`start · error · result`) pay off the accepted cost from
   `a-failing-run-ends-at-its-result`, and settle that **javac sends every diagnostic in one
   frame**, not one frame per diagnostic.

## Pages this source updated

[[decisions/2026-08-12-a-language-is-supported-when-its-failures-are-too]] ·
[[decisions/2026-08-11-a-failing-run-ends-at-its-result]] ·
[[decisions/2026-08-11-a-grading-is-its-whole-session]] ·
[[concepts/tests-that-explain-defects]] ·
[[concepts/assumption-vs-measurement]]
