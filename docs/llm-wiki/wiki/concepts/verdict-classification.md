---
type: concept
project: programmers-tracker
tags: [protocol, verdict, debugging-pattern]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md]
---

# Verdict Classification and Its Traps

## `submit` alone cannot distinguish compile errors

The `msg` string in the `testcase` message is the only clue; `exitCode` and `stderr` never arrive.

| Type | `msg` |
|---|---|
| Wrong answer | `실패 (0.01ms, 75.3MB)` |
| **Runtime error** | `실패 (런타임 에러)` |
| **Compile error** | `실패 (런타임 에러)` ← identical |
| Timeout | `실패 (시간 초과)` |

Compile errors and runtime errors arrive as the same string. Distinguishing them requires
the `run` action — that is where full compiler output and stack traces arrive.

**This is why `run` must always be recorded.** It is not committed, but it is the only
source of full error text.

## Silent failure — the challengeable_id confusion

The longest-blocking issue. The page contains two similar-looking numbers.

```
data-challengeable-id="14643"   ← subscription identifier
<input id="49598" data-type="code">  ← codes payload key
```

If you mistakenly send the codes key as `challengeable_id`, **the subscription is confirmed
and the testcases even run normally**, but only the final result fails with `{"type":"error"}`.
The symptom was "16/16 passed but nothing recorded," which made the cause hard to trace.

> **Generalization**: in an external protocol, *partial success* is not success. Do not
> conclude a parameter is correct just because intermediate steps passed.

## Timeouts take 87 seconds

A normal judging run takes 6–9 seconds; a timeout run takes **87 seconds**. A 60-second
client timeout would cut off a legitimate timeout verdict mid-run. Minimum 120 seconds.

→ [[syntheses/protocol-reverse-engineering]]
