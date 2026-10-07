---
type: source
project: programmers-tracker
tags: [mcp, diagnosis, storage, verdict, measurement, orchestration, failed-attempts]
created: 2026-10-08
updated: 2026-10-08
sources: [raw/sessions/2026-10-07-repairs-not-verdicts.md]
---

# 2026-10-07 session summary — repairs, not verdicts

## Key claims

1. **The owner wants their own recurring mistakes named** (argument order, method names, syntax
   slips) before a coding test. Calling every MCP tool showed the evidence did not exist: no run kept
   its code. The owner chose to have them diagnosed on demand from repair steps and never stored
   (option A), and to have every run's code published with the records.
2. **The design's `recordId` field was dropped before any code**: 242 live log lines formed 121
   `(ts, captureKey)` groups, so #343 needed no schema change.
3. **The owner chose subagents over orchestration** for the build. Each task went through an
   implementer, a spec review and a quality review.
4. **Four PRs, each verified live**:
   - #348: 131537 reads 3 submits / 10 runs, where it read 1 / 5.
   - #350: a run rejected by MySQL → COMPILE_ERROR, a wrong submit → WRONG. A rejected submit was
     measured beforehand to say `실패 (런타임 에러)`.
   - #352: three runs, three `runs.jsonl` lines.
   - #357: `repair_steps` returned the one correction.
5. **The design's `codeUncertain` could not work.** The next `start` is not visible at attach time.
   The race was measured instead: code arrived 0.24–0.53 s after each record, against a fastest
   human gap of 1.6 s.
6. **Review caught three things no test had:**
   - one problem split across two part/level buckets;
   - a code-read bound that took three rounds (the root, then a link inside `problems/`, then a
     linked `problems`);
   - Claude Code's 2,048-character cut, which a 3,000-character test had passed for weeks.

## Pages this source updated

[[decisions/2026-10-07-mistake-patterns-are-diagnosed-not-stored]] ·
[[decisions/2026-10-07-a-record-is-its-time-and-its-bytes]] ·
[[decisions/2026-10-07-database-failures-that-said-something]] ·
[[decisions/2026-10-07-every-run-keeps-its-code]] ·
[[decisions/2026-10-07-repair-steps-are-served-not-judged]] ·
[[decisions/2026-10-07-no-reader-follows-a-link-out-of-problems]] ·
[[entities/claude-code-mcp-client]] · [[concepts/assumption-vs-measurement]] ·
[[concepts/orchestrated-implementation]]
