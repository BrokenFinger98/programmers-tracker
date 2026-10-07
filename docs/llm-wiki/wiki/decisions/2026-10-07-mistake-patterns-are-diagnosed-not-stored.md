---
type: decision
project: programmers-tracker
tags: [mcp, diagnosis, storage, interpretation-boundary]
author: BrokenFinger98
created: 2026-10-07
updated: 2026-10-07
sources: [raw/sessions/2026-10-06-the-history-that-folded.md]
---

# Mistake patterns are diagnosed on demand from repair steps, and not stored

## Context

The owner wants the records to say what they keep getting wrong — not a problem type but a habit:
argument order, a mixed-up method name, a recurring syntax slip — and to drill those habits before
an exam. That is a claim about the learner, which [[decisions/2026-08-10-scheduling-is-not-diagnosis]]
leaves to the AI. The evidence for it is not the failure but the correction: what changed between
a failed grading and the next attempt. Argument-order mistakes often compile and leave no error
text; the diff is the only witness.

Calling every MCP tool on 2026-10-07 showed the evidence does not exist: runs keep no code (one
overwritten `Solution.<ext>`), and lesson 273711's eleven runs — four rejected by MySQL for column
names, six wrong from reading a relation backwards — left none of their code. The design is
`docs/superpowers/specs/2026-10-07-mistake-patterns-design.md`.

## Options considered

1. **Diagnose on demand.** The server returns *repair steps* (failed grading, next attempt, error,
   diff); the AI clusters them in the session; nothing it concludes is stored.
2. **Store the AI's notes** in an annotation store the AI may write — stable pattern names and a
   countable trend, at the price of the first exception to MCP being read-only
   ([[decisions/2026-08-06-mcp-read-slice]]), which exists so a prompt-injected AI cannot change
   what is recorded.
3. **Classify mistakes in the server** by matching compiler messages — the rule-based analyzer the
   constitution forbids, and blind to mistakes that compile.

## Decision

Option 1, chosen by the owner on 2026-10-07. Every run's code is kept in a per-problem
`runs.jsonl`, committed with the records; a pure `RepairSteps` calculator pairs gradings; MCP gains
`repair_steps`, `get_problem(include=…)`, `stats` grouped by part and level, and an `exam_prep`
prompt that asks the model — in the open — for the interpretation the server does not do.

## Rationale

A repair step is a recorded fact, so serving it keeps the server on the counting side of the line
[[decisions/2026-08-12-the-server-counts-and-names-nothing]] drew. One learner's history holds
hundreds of steps after months, small enough to cluster in one pre-exam session, so persistence
buys stability of names, not feasibility. Everything option 1 stores is what option 2 would read,
so the step to it stays open.

## Accepted costs

- A pattern can be named differently from one session to the next, and a habit's decline is
  recomputed rather than counted.
- Every run's code is published with the records — the owner's call; it narrows a template comment
  that kept run frames out of git.
- History before this ships has no run code; the first sessions see only what was solved after it.
- Runs pressed seconds apart can have the next run's code attributed to them, because the code is
  fetched after the grading settles (protocol §15.1); the design marks such runs
  `codeUncertain` instead of preventing it.
- The records must be correct first: #343 and two verdict gaps measured on 2026-10-03 and
  2026-10-07 come before any of this.

## Outcome

Approved 2026-10-07 (#346). Implementation follows as separate issues, #343 first.
