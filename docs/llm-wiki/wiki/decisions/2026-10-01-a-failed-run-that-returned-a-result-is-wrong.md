---
type: decision
project: programmers-tracker
tags: [protocol, verdict, measurement, sensor]
author: BrokenFinger98
created: 2026-10-01
updated: 2026-10-01
sources: [decisions/2026-08-05-failure-taxonomy, concepts/verdict-classification]
---

# A failed run that returned a result is WRONG

## Context

`VerdictResolver` classifies a failure by its message string and nothing else, because on the
submit path the message is the only clue there is (protocol §7), and because a failure that
matches nothing measured must stay `UNKNOWN` rather than become its nearest neighbour — the
silent-wrong-data outcome the constitution ranks worst.

A database `run` carries no message at all, whether it passed or failed (protocol §6). A
passing one resolves to PASS from `passed: true`. A failing one had never been measured until
2026-10-01, when the owner ran a wrong query three times on lesson 131118: every run was recorded
`UNKNOWN`, and the sensor showed purple `?` — the state reserved for *the server saw a result it
could not record* — on an ordinary wrong query. The purpose of that state is to alarm on the one
thing worth alarming on; three false alarms in an afternoon teach the owner to ignore it.

## Options considered

1. **Treat `passed: false` with no message as WRONG everywhere.** On the submit path a null
   message has no measured counterexample yet, and the rule exists because the next protocol
   change will produce one; a blanket rule would file it as a wrong answer.
2. **Let the resolver know the problem kind** and special-case database runs. The resolver is a
   pure calculator over testcases; the kind is a channel property it has never needed, and
   "database" is not the fact that matters — the returned table is.
3. **Carry the one fact the frame has that the message lacks.** The failing frame carries the
   table the query returned; a query that ran and produced a table that did not match is a wrong
   answer by definition. A domain field says whether the case reported a result of its own; the
   resolver uses it only when there is no message.

## Decision

Option 3. `TestcaseResult.returnedResult` (nullable, absent on paths that never report one and on
records written before it existed) is set by the mapper from the finish frame's `returned_rows`.
`verdictOf` reads a null message as WRONG when a result was returned and as unknown otherwise.
The measured frame is the fixture `sql-run-wrong.jsonl`, scrubbed; protocol §6 and §15 record it.

## Rationale

The rule stays "measured only": the frame was captured live, the fixture is its shape, and the
test goes through the assembler. A failure with neither a message nor a result still resolves to
nothing — the honest answer when nothing was said — so the direction that matters (never a
confident wrong verdict) is unchanged. The field is named for what it means, not for the wire
spelling, so the domain learns nothing about `returned_rows`.

## Accepted costs

- One more nullable on a record type that is already all nullables, and one more "absent means
  never reported" to read correctly. Old records keep their `UNKNOWN`; nothing is rewritten.
- A database run that fails with an SQL error (the site shows `실패 (1064, …)`) was seen on
  screen on 2026-09-29 but never captured on the wire — the subscription was dead that minute.
  Whether that frame carries a message is unmeasured; if it carries none and no table, it stays
  `UNKNOWN`, which is correct until it is measured.

## Outcome

Issue #341. Resolver tests for both branches, a mapper test on the new fixture, an assembler test
through it (`RUN`, `JUDGED`, `WRONG`). Live verification: a wrong SQL run on the rebuilt
container shows `✓` with `run WRONG 0/1`.
