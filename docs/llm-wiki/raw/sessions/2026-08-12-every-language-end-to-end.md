# 2026-08-12 — every supported language, driven end to end

Raw session record. Immutable (wiki schema §1).

The owner asked what was next, then: *"시험모드 빼고 다 진행해. 그리고 현재 제공가능 언어에
대해서 다 테스트 해봐. e2e로"* — every language the server itself supports, exercised against
the real judge.

---

## Where the work item came from

Not from a failing test. From counting two things that should have matched:

- `FileDerivedArtifacts.GENERATORS` — seven languages
- `VerdictResolver.compilerDiagnostics` — two patterns

and from a KDoc that had already written down its own gap:

> Not here on purpose: `IndentationError` and `TabError` … Neither has been captured. One
> line each when they are.

Same source as #191, #193, #205 and #207: **the project's own record of what it does not
know.** Four days running now.

---

## What the measurement changed about the diagnosis

The issue predicted four uncovered languages (cpp, c, kotlin, csharp). The measurement said
**one**.

clang and kotlinc print `file:line:column: error:`. The javac pattern `:\d+: error:` matches
the `:15: error:` tail. So C, C++ and Kotlin were classified correctly by an accident nobody
had noticed, and node's `SyntaxError:` was caught by the python pattern the same way.

C# was the real miss, and it is the one shape that could not be guessed from the others:

```
/Solution0.cs(10,31): error CS1002: ; expected [/Solution.exe.csproj]
```

Bracketed position. No colon-digit-colon anywhere. Recorded as RUNTIME_ERROR — live, in the
log, twice today before the fix.

The lesson is not "four were fine". It is that **six of seven were being classified by two
patterns written for two other languages, and nothing said which**. Tighten the javac pattern
and three languages break with no test naming them.

---

## A confound caught, and how

The first Kotlin capture came back with no compiler text at all:

```
main 메소드가 정의되지 않았습니다
```

Filed as "Kotlin compile errors are invisible" for about ninety seconds. Then: run the
**correct** Kotlin and see what happens. Same message.

So it was not a compile failure report — it was the harness failing to find `main`, because
I had written `fun main()` and the editor template ships `fun main(args: Array<String>)`.
I had overwritten the template with a scaffold of my own and measured the scaffold.

Getting the real template needed the 초기화 button, which opens a modal. `window.confirm`
was neutralised first — a blocking browser dialog kills the automation session outright.

**The rule that saved it**: a negative result — *"no diagnostic arrives"* — is the kind of
claim [[concepts/tests-that-explain-defects]] says to distrust, because it is what a broken
setup produces. The check was one extra run.

Worth keeping anyway: Programmers rejects Kotlin's modern no-arg `fun main()`, with a message
that says nothing about signatures, identically for working and broken code.

---

## Results

Fourteen actions on lesson 181952 (Lv0), one broken run and one correct submit per language.

| language | wire name | compile failure | submit |
|---|---|---|---|
| java | `java` | ✅ | PASS 3/3 |
| python3 | `python3` | ✅ syntax, ❌ indentation, ❌ tab | PASS 3/3 |
| cpp | `cpp` | ✅ coincidence | PASS 3/3 |
| javascript | `javascript` | ✅ coincidence | PASS 3/3 |
| kotlin | `kotlin` | ✅ coincidence | PASS 3/3 |
| c | `c` | ✅ coincidence | PASS 3/3 |
| csharp | `csharp` | ❌ RUNTIME_ERROR | PASS 3/3 |

All seven wire strings match their generator keys; all eight runner files were written. Both
had been assumed since #37 and neither had been seen.

Every capture is whole — `start · error · result` — which pays off the accepted cost from
[[decisions/2026-08-11-a-failing-run-ends-at-its-result]]: a fixture that never terminated,
driving a capture that recorded nothing.

---

## A wrong reading, and the check that caught it

Mid-sweep the log looked catastrophic: 100 lines, 49 distinct capture keys. *"Every record is
written twice — every count `stats` reports is doubled."*

Wrong. The log is append-only and the second line per key is the **code-attachment
correction** — `codePending: true → false`, `codePath` filled. `RecordHistory.of()` collapses
them. 48 of 49 pairs are exactly that, and zero pairs are byte-identical.

What produced the error: counting `captureKey` occurrences, and reading a `tail -2` dump
whose second record was truncated before `codePending`. The conclusion was announced before
the second line was read to its end.

The one pair that was not a correction turned out to matter, though — below.

---

## The ADR quantifier that broke

`b69e3464f5db7f94` has four lines: two gradings, two minutes apart, **same capture key**.

[[decisions/2026-08-11-a-grading-is-its-whole-session]]:

> Timings make that vanishingly unlikely for an algorithm problem … **SQL is where it is
> plausible**, because SQL sends no per-case timing at all

An algorithm problem, and it collided. The reason the ADR missed: **a failure that never
reaches a testcase has no timings either.** Two identical compile failures are byte-identical
frames whatever the language.

No behaviour change — the live path stopped consulting the index at #159, precisely so a
repeated SQL submission would not vanish, and both runs were recorded correctly. The replay
path still dedups, so a crash with two identical failed runs queued would fold them into one.
Narrow. But the ADR said "vanishingly unlikely" about a thing that happened within an hour of
trying, which is the same *observation real, quantifier invented* pattern that produced #191.

---

## Mechanics worth not rediscovering

- The editor is **CodeMirror 5**: `document.querySelector('.CodeMirror').CodeMirror.setValue(...)`
  sets code without fighting auto-indent and bracket-closing. Typing into it mangles the code.
- **A click issued in the same `browser_batch` as a `navigate` does not register.** Every run
  and submit needed the click in a separate call. Cost roughly one wasted round trip per
  language before the pattern was clear.
- Language switches via `?language=<name>` — no dropdown needed, and the sensor extension
  re-announces on load.
- Drafts persist per language, so the first read after navigating is not the template.
