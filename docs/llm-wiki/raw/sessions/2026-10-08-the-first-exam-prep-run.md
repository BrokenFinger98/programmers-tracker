# 2026-10-08 — the first exam_prep run

Raw session record. Immutable (wiki schema §1). Session `b240e44e`, written after the ingest of
2026-10-08 (#369), when the owner came back and ran the prompt the model cannot run.

---

## The command, twice

The owner first ran `/mcp`, and Claude Code answered "Reconnected to programmers-tracker." Then the
session's deferred tool list gained `repair_steps`. It had been missing since #357 shipped. This is
the cached discovery the exam-prep ADR warned about, seen from inside the session.

The first attempt began with a space: ` /mcp__programmers-tracker__exam_prep mysql`. Claude Code sent
it as plain text, not as a command, and the model received only that string. Retyped at the start
of the line, it ran. The injected message opened with "Prepare me for a coding test…" and carried:

- `Scope: language "mysql".`;
- the line saying only `repair_steps` takes the scope;
- the empty-answer line.

So the slash command path works end to end in Claude Code 2.1.285: discovery, `prompts/list`,
positional arguments, `prompts/get`, and injection.

## What the model called

| Call | Answer |
|---|---|
| `stats(groupBy=part)` | 36 submits. SELECT: 34 submits over 23 problems, all passed, 21 on the first submit, `runsBeforePass` 1. The basic-training track: 2. |
| `stats(groupBy=level)` | Lv1: 17 problems, all first-submit passes, `runsBeforePass` 1. Lv2: 4 problems, 2 first-submit, **11**. Lv4: 2 problems, **10**. Lv0: 2. |
| `repair_steps(language="mysql")` | `count` 20, `total` 63, `truncated: true` |
| `repair_steps(language="mysql", limit=63)` | 63 steps, every one in SELECT |
| `list_problems(status=untouched, part="SELECT")` | 10 problems |

The model followed the truncation rule as written: one call to see `total`, one with `limit` set to
it. No answer carried `incompleteHistory`.

## What the answer could and could not stand on

**58 of the 63 steps carried no diff.** Their `noDiff` was `codeUnknown`, `fromCodeUnknown` or
`toCodeUnknown`. Every step from 10-01 to 10-03 touches a run, and run code is kept only from the
2026-10-07 tracker on (#352). The other five, lessons 59035 and 59036 on 10-07, were measurement
runs the owner made on request during #349, #351 and #353. The conversation says so; the records
cannot. One of them carries the only diff in the answer. The model left all five out of the
patterns and said why.

So **every pattern was named from what the judge refused**, MySQL's `failedMessage`, not from a
diff:

| Pattern | Judge's output | Lessons | Records |
|---|---|---|---|
| A — a name the schema does not have | 1054 unknown column, 1146 unknown table | 273711, 131537, 133025 | 6: `2026-10-03T16:15:38…#a237c3442ddeb5cc`, `16:15:57…#4fb003eb9ff84bd6`, `16:16:33…#21a4173316e208be`, `16:57:13…#b1a6d6b8628ece15`, `2026-10-03T15:21:11…#bbdc3ddeb792e47e`, `2026-10-01T08:57:42…#3dab5e035907e3ca` |
| B — syntax MySQL does not take | 1064 syntax, 1305 unknown function | 276034, 131536, 132201 | 4: `2026-10-03T17:29:32…#3c717459b31b7704`, `17:29:37…#e332f6b12b4f63e2`, `2026-10-02T09:36:04…#e04dd8545b0a40ce`, `2026-10-01T15:24:37…#2f5bfb4b89bf7bf6` |
| C — a submit right after a WRONG run | `실패`, no message | 131536 (2 s later), 273711 (20 s later, 6 of 6 cases failed) | 4: `2026-10-02T09:32:53…#0466ae202ee51214` → `09:32:55…#d76beae6b6de525b`; `2026-10-03T16:56:10…#23413fe56626da36` → `16:56:30…#577787f672f01fbc` |

- Pattern C's two lessons are the two Lv2 problems not passed on the first submit, which `stats`
  had already shown.
- The 1222 UNION column-count error, seen once, was not named a pattern. A pattern seen once is not
  a pattern.
- The model said that pattern B's three instances are three different slips, so the grouping is
  looser than A's.

**What it said it could not support:**
- Most steps ended WRONG with no message and no diff, so what was wrong in them cannot be told.
  Lesson 131118 has nine unresolved runs in a row with no message.
- Nine problems over three days in one language is a small sample.

## What the run changed

The text asked the model to "Name each pattern by what its diffs show" and said nothing about a
step without one. This model fell back to the judge's output on its own and said so; another
might name nothing at all. #370 writes the fallback into the text: where a step has no diff, name
the pattern by the judge's own output (`errorText`, `failedMessage`) and say the diff is missing.

This also means the diagnosis gets better without any code change. From 2026-10-07 on, every run
keeps its code, so a later `exam_prep` over newer steps will have diffs to name patterns by.
