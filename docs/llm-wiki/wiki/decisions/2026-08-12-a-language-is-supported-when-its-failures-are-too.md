---
type: decision
project: programmers-tracker
tags: [verdicts, protocol, languages, fixtures, measurement]
author: BrokenFinger98
created: 2026-08-12
updated: 2026-08-12
sources: [raw/sessions/2026-08-12-every-language-end-to-end.md]
---

# A Language Is Supported When Its Failures Are Classified Too

## Context

`FileDerivedArtifacts.GENERATORS` lists seven languages — java, python3, cpp, javascript,
kotlin, c, csharp — and its comment states the bar plainly:

> a language lands here only after its execution suite has actually run its output

That bar is about the **runner we generate**: does the scaffold we write compile and run on
the developer's machine. It says nothing about the other half, which is what happens when
the *learner's* code fails. `VerdictResolver.compilerDiagnostics` held two patterns:

```kotlin
Regex(""":\d+: error:"""),   // javac
Regex("""SyntaxError:"""),   // python3
```

Two patterns, seven languages, and no record anywhere of which five were uncovered — or
whether they were uncovered at all. Reading the code cannot answer it: the question is what
each toolchain prints, and that lives on Programmers' servers, not here.

This is the second occurrence of the shape. #160 filed a Python `def` missing its colon as
RUNTIME_ERROR, because the only pattern present was javac's and nothing said so. Python was
added. **The other five were left, and the fix did not record that it was partial.**

## Options considered

**A — Write the patterns from knowledge of the toolchains.** g++, kotlinc and csc formats
are well known, and this costs nothing. Rejected: it is exactly how #160 happened, and the
constitution names guess-based work as forbidden. Worse, a pattern written from memory that
happens to be right teaches nothing — the file would still not say what was measured.

**B — Measure each language, one broken run apiece.** Break the compile deliberately on a
Lv0 problem in each of the seven, capture the `error` frame, and derive the pattern from the
bytes. Costs seven runs against Programmers.

**C — Measure, and also submit a correct solution in each.** B, plus one passing submit per
language: fourteen actions rather than seven.

## Decision

**C.** Every supported language is broken on purpose and captured, *and* passed on purpose
and recorded, on lesson 181952 (Lv0). Nine fixtures, one test table, and a bar restated:

> A language in `GENERATORS` owes a compile-failure fixture.

## Rationale

**The measurement contradicted the code's own story, in both directions.**

Four of the five "uncovered" languages were already classified **correctly, by coincidence**.
clang and kotlinc print `file:line:column: error:`, and the javac pattern matches that on its
`:column: error:` tail. node prints `SyntaxError:`, which the python pattern catches. So the
list had been quietly doing the work of six languages while naming two.

An accident that holds is still an accident. It survives exactly until a toolchain drops the
column or renames the exception, and until then **nothing in the tree says which languages
are resting on it** — a maintainer tightening the javac pattern to `:\d+: error:$` would
break C, C++ and Kotlin with no test naming any of them.

**One language was genuinely wrong**, and it was not one that reading would have singled out:

```
/Solution0.cs(10,31): error CS1002: ; expected [/Solution.exe.csproj]
```

C# brackets its position, so no colon-digit-colon appears anywhere. Every C# compile failure
was recorded as RUNTIME_ERROR — a verdict the learner never earned, in a permanent record,
which the constitution ranks as the worst outcome available.

**Two more were wrong in the language the code claimed to support.** `IndentationError` and
`TabError` are `SyntaxError` subclasses that print their own names, so `SyntaxError:` misses
both. The KDoc knew:

> Not here on purpose … Neither has been captured. One line each when they are.

A year of that sentence is a year of Python indentation mistakes filed as runtime errors. The
honest reading is that a stated gap with no owner is a gap, not a plan.

**Why the correct submits too (option C over B).** Only java, python3 and mysql had ever
produced a record, so five wire language strings were unverified against the generator keys.
If `csharp` had arrived as `c#`, no runner would be generated and **nothing would say so** —
the map lookup misses and the artifact is simply absent. All seven matched, and all seven
runners were written; that is now measured rather than assumed (protocol §15.5).

## Accepted costs

- **Fourteen actions against Programmers' judge in an hour.** Below what a learner practising
  two languages does in a sitting, and confined to one Lv0 problem, but it is their compute
  for our benefit and development-rules §9.3 says to say so plainly.
- **Nine more fixtures, and a table that must grow with the generator list.** The table is
  the enforcement — a language added without a row is invisible again — but nothing makes
  adding the row mandatory except the sentence now in development-rules §6.2. A guard could
  cross-check `GENERATORS` against the fixture directory; it is not built, because the
  generator list has changed three times in eight days and the test table is read every time
  someone touches verdicts.
- **The four coincidental matches are still coincidences.** They are now *documented*
  coincidences with fixtures pinning them, which is the difference between a trap and a known
  sharp edge — but the C++ entry still lives inside a pattern whose comment says "javac".
  Splitting it into one pattern per language would read better and would match strictly less,
  and matching less here means misclassifying more.
- **`메모리 초과` is still unmeasured** (§14). Triggering it means deliberately exhausting
  memory on their judge, which is a different order of imposition than a syntax error.

## Outcome

Three misclassifications fixed, six languages' shapes recorded, and the accepted cost from
[[decisions/2026-08-11-a-failing-run-ends-at-its-result]] — a run-error fixture that never
terminated, so the capture it drove recorded nothing — closed by nine captures that are whole.

Two findings fell out that were not the point:

- **javac sends every diagnostic in one frame.** The earlier ADR said "one error frame per
  compiler diagnostic"; a two-error capture arrives as a single frame ending `2 errors`. The
  two frames in `algorithm-run-error.jsonl` are therefore of different provenance, not one
  session — recorded in the fixture README rather than quietly corrected.
- **A byte-identical capture-key collision happened on an algorithm problem.**
  [[decisions/2026-08-11-a-grading-is-its-whole-session]] calls that vanishingly unlikely
  outside SQL, because algorithm timings jitter. **A failure that never reaches a testcase
  reports no timing either**, so two identical compile failures collide. The live path does
  not dedup (#159), so both were recorded; the replay path does, so a crash-recovery would
  fold two real failures into one. Narrow, and the ADR's quantifier was wrong.

Also measured and not a defect of ours: Programmers invokes Kotlin's `main(String[])`, so a
top-level `fun main()` is compiled and then never found. The message names a missing main
method and is **identical for correct and broken bodies**, which confounded two readings
before the editor template was checked.


## Amended 2026-08-12 (#222): the same error one path over

This ADR is about a measurement taken on one language and generalised to six. The identical
mistake was sitting one axis away, and it took going after the missing verdict to find it.

`timeoutMessage` was measured on the **submit** path. The **run** path has its own limit — ten
seconds, against the submit's ~87 — and announces it in a sentence sharing no words with the
one that was measured. So a run that merely ran long matched nothing, fell through to the
compiler-shape branch, and was recorded as a RUNTIME_ERROR: slow code, filed as crashed.

The counter-practice generalises past languages, then. **A pattern measured on one path is
evidence about that path.** The `action` axis (run vs submit) and the `kind` axis (algorithm vs
database) are as capable of carrying a different sentence as a different compiler is — protocol
§6 already says database never sends `finish`, which is the same lesson in a shape this project
accepted years-equivalent ago and did not carry across.

Kept as a second pattern rather than a loosened first one, for the reason the language entries
are kept separate: a regex wide enough to catch both would stop recording which was seen.
