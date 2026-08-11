---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [runner, debugging, codegen, languages, yagni]
created: 2026-08-07
updated: 2026-08-11
sources: [decisions/2026-08-06-shipped-problem-catalog, raw/sessions/2026-08-06-catalog-runners-and-the-record-repository.md, raw/sessions/2026-08-07-adversarial-review.md]
---

# The server generates per-problem runners, and refuses before it guesses

Date: 2026-08-07 · Status: accepted · Issue: #37

## Context

The record carried the grading but not the examples, and nothing turned a wrong answer into
a debugging session. Design §2 step 4 promised "open the local file in IntelliJ — the server
has already generated a runner", and it was the last promised artifact not built.

Two questions had to be settled first, and both were the owner's.

**Who generates?** An alternative design had the server carry only the example data and an
MCP-connected AI write the runner in the user's own language and project. It fits the
"server collects, AI interprets" principle and costs no per-language maintenance. **The
owner declined it**: the runner must work out of the box, without an AI in the loop.

**Which languages?** The original issue said IntelliJ + Java, which assumes every user's
stack. The owner reframed it: support the languages people actually solve in, by usage.
Programmers publishes no statistics, so the pagination depth of one problem's shared
solutions stood in (lesson 120804): java 107 pages, python3 105, c 90, javascript 73,
cpp 36, csharp 19, swift 9, kotlin 7, go 3 — a biased Lv0 sample, stated as such. Blended
with intent, the support order is **java → python3 → cpp → javascript → kotlin → c →
csharp**; SQL has no runner concept; the rest are refused until demand shows.

## Options considered

- **AI generates over MCP** — declined by the owner, above. Preserved here because it
  remains the fallback for every language the server does not support yet.
- **One runner language (Java) as specified** — rejected as the end state; accepted as the
  starting point, because it is the owner's language, rank 1 in the measurement, and the
  language of every measured protocol capture.
- **Server generates, honestly scoped (chosen).**

## Decision

1. **A pure calculator per language** (`domain/calc/runner`): `(user's code, measured
   examples) → runner source, or a refusal that says why`. No I/O, mock-0 tests.
2. **The line that may not be crossed: a runner that compiles and tests the wrong thing is
   worse than none.** Everything unparseable or mismatched refuses with an actionable
   reason — never a best-effort file. The types come from the user's own `solution(...)`
   signature, the only trustworthy source: the wire sends `"3, 2"` with neither names nor
   arity, and JSON cannot tell an `int` from a `long`.
3. **Both measured shapes** (protocol §7.1): solution-style builds typed arguments and calls
   `solution(...)`; main-style feeds the input to stdin verbatim and compares stdout,
   trailing-newline-insensitively. `main` wins when both appear — a `solution` helper inside
   a `main` program is the user's refactoring, not a harness entry point.
4. **The §7.1 wire format is parsed in exactly one place** (`ExampleValues`): bracket-wrap
   for argument lists, raw control characters escaped only inside quoted regions. Every
   consumer that parsed it themselves would trip on the raw-newline trap, on multi-line
   problems only, looking problem-specific.
5. **Self-contained output.** Plain `main`, standard library only, runs with
   `java RunnerTest.java` (JEP 330) — no project scaffolding lands in the records repo.
6. **Regenerated on every attachment, and a refusal deletes a stale runner.** A runner built
   from yesterday's code tests something the user is no longer looking at.
7. **A language earns "supported" only by execution**: its generated runner is compiled and
   run in a real child process against measured examples, reproducing the judge's verdict —
   pass passes, wrong fails naming the example. Text assertions prove we wrote what we
   meant; only running proves what we meant is right.

## Accepted costs

- **Per-language maintenance, accepted knowingly by the owner.** Six more generators are
  promised, each with its own type mapping, templates and execution tests.
- The signature parser is **shallow and refuses generics** — deliberately. A deep Java
  parser that is silently wrong about an edge case produces exactly the forbidden runner.
- Refusal reasons live in the log, not yet in the problem README. The README states the
  runner when it exists; the reason-when-absent line is deferred.
- The measurement behind the language order is one Lv0 problem. The top is stable under any
  sample; the middle is not, and the order may be revisited when real usage data exists.

## Outcome

Java shipped 2026-08-07: `ProblemShape`, `ExampleValues`, `JavaSignature`, `JavaRunner`,
wired into `CodeAttachment` so the runner rides the same trigger as the code it tests.
834 tests; the execution suite compiles and runs generated runners in child JVMs for both
shapes, both verdicts. python3 is next in the support order.

**Series completed 2026-08-07** — all seven measured languages shipped, in order: java
(#37), python3 (#78), cpp (#80), javascript (#82), kotlin (#84), c (#86), csharp (#88).
Each earned "supported" through an execution suite against its real toolchain, and the CI
proof-ran gate holds all seven suites to having actually run (a missing results file fails
the same gate). Per-language facts worth keeping:

- Every skeleton fact was **captured from the actual editor before writing** (Orca
  browser, 2026-08-07); one capture drifted to a stale page and was caught by a
  title-match guard — guard every capture.
- The two shapes and the §7.1 value grammar held across all seven languages; what varied
  was *packaging*: C++ shares one translation unit (hence `runner_` prefixes and named
  locals), Kotlin needed a top-level bridge past its own harness `main`, C expands one
  wire value into two or three physical arguments, C# ships a csproj beside the harness
  with globbing off and the entry pinned.
- Two languages settled open questions by experiment-as-test: Kotlin's `readLine()`
  follows a swapped `System.in` (two-example test pins it), and C#'s skeleton
  `Console.Clear()` under redirection is measured per-OS by its suite — **answered on the
  suite's first CI outing** (run 31147247460): macOS/Linux no-op it, **Windows throws**;
  in a terminal it clears the screen everywhere. The generator therefore emits a
  stated-skip guard, only for solutions that actually call `Clear`, and the suite pins
  both behaviours per OS.
- csharp's execution proof **cannot run on the dev machine** (broken x86_64 dotnet host
  on arm64); CI's three runners carry that proof, which merge-only-on-green keeps ahead
  of shipping.
- Not in scope, still: go/ruby/scala/swift (below the measured usage cut), mysql/oracle
  (no runner concept for SQL).

Related: [[decisions/2026-08-06-shipped-problem-catalog]] ·
[[concepts/assumption-vs-measurement]].
