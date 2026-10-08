---
type: source
project: programmers-tracker
tags: [mcp, prompt, acceptance, measurement]
created: 2026-10-08
updated: 2026-10-08
sources: [raw/sessions/2026-10-08-the-first-exam-prep-run.md]
---

# 2026-10-08 session summary — the first exam_prep run

## Key claims

1. **Spec §6's acceptance is met.** The owner ran `/mcp__programmers-tracker__exam_prep mysql` in
   Claude Code after `/mcp` → Reconnect. The prompt arrived scoped. The model called `stats` twice,
   `repair_steps` twice (truncated at 20 of 63, then `limit=63`) and `list_problems`. It answered
   with three patterns, each citing record ids and a problem count.
2. **A leading space turns the command into text.** ` /mcp__…` was sent to the model as a plain
   string; at the start of the line it ran. Reconnecting also brought back `repair_steps`, which
   the session's cached discovery had been missing since #357.
3. **58 of 63 steps had no diff, so every pattern was named from the judge's output.** Run code is
   kept only from the 2026-10-07 tracker on. The five steps that had code were measurement runs,
   which the records cannot tell from mistakes.
4. **The text now says what to do without a diff (#370).** Where a step has no diff, the pattern is
   named by the judge's own output, and the answer says the diff is missing.

## Pages this source updated

[[decisions/2026-10-07-exam-prep-asks-in-the-open]]
