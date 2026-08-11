---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [capture, durability, pipeline, raw-log, codefetch]
created: 2026-08-05
updated: 2026-08-11
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md, raw/sessions/2026-08-05-capture-pipeline-built-end-to-end.md]
---

# Capture is three stages, and the raw log is the durable queue

Date: 2026-08-05 · Status: accepted · Issue: #8

## Context

The design's capture sequence (design §3.2) puts **both** persistence steps *after* the
CodeFetch round-trip, and CodeFetch (§4.4) specifies no timeout, no retry and no failure
branch. One failed HTTP fetch therefore discards the grading verdict entirely.

The two artifacts have opposite recovery properties:

- **The verdict is unrecoverable.** Programmers has no submission-history API — an
  exhaustive bundle audit found none (protocol §11): *"A grading result not captured at
  that moment is lost forever."*
- **The code is re-fetchable.** The problem page keeps the last saved code and can be
  fetched again later (protocol §10).

So the design gates the irrecoverable artifact on the recoverable one. Separately,
development-rules §2.4 already requires keeping original frames alongside parse results,
and a crash between "frames received" and "record written" loses everything in flight.

## Options considered

- **Keep the current single-stage flow, add retries to CodeFetch** — narrows the window
  but never closes it: a crash, an expired cookie, or a 429 (rate-limit rules are
  unverified, protocol §14) still destroys the verdict.
- **In-memory queue between capture and recording** — decouples the two, but the queue
  contents die with the process, which is exactly the loss we are trying to prevent.
- **Three stages with a durable raw log (chosen)** — the raw append is the commit point;
  everything after it is retryable.

## Decision

Capture is three stages with distinct failure semantics:

| Stage | Work | Rule |
|---|---|---|
| **1. Hot** | Every received frame appended to a raw session log | Must never block or fail. No parsing decisions, no network, no derived writes |
| **2. Warm** | Session assembly → verdict → record write | Runs off the socket read loop; retryable from the raw log |
| **3. Cold** | CodeFetch attach, git commit/push | Failure is recorded, never fatal; retried later |

**The raw log is the queue.** No separate in-memory queue exists. Unprocessed sessions
are discovered by scanning the raw directory at startup and after failures.

**Raw location (option A):** frames are appended to `.ps/raw/<ts>-<lessonId>.jsonl` while
the session is live, and the file moves to `attempts/00N.raw.jsonl` (development-rules
§2.4) once the session completes and its problem directory and attempt number exist.

**Record ordering:** the record is written with the verdict as soon as the session
terminates. Code is attached afterward; if CodeFetch fails the record persists with a
`codePending` marker and the fetch is retried.

## Rationale

- Puts the commit point at the only moment we control: frame arrival. After stage 1,
  no downstream failure can destroy a grading result.
- The durable queue costs nothing extra — development-rules §2.4 already mandates
  keeping the original frames, so stage 1 is work we owe regardless.
- Enables re-analysis: verdict logic can be re-run over stored raw frames, which is the
  same property the Functional Core split was chosen for (development-rules §3).
- CodeFetch has an irreducible race anyway — code is autosaved while editing (protocol
  §10), so a post-result fetch may return edited code. Treating code as a late, retryable
  attachment makes that honest instead of pretending the fetch is authoritative.

## Accepted costs

- Two writes per session (raw, then derived record) and a move — negligible at
  human-driven event rates, but real duplication on disk.
- Startup must reconcile orphaned raw sessions, which is code that only ever runs after
  a crash and is therefore easy to get wrong. It needs its own tests.
- `codePending` is a second-class record state that every consumer (MCP, Obsidian
  queries) must tolerate.
- Whether `run` even saves code is still unverified (design §13); until that measurement
  exists, `run` records may attach code from a previous edit.

## Outcome

Recorded 2026-08-05 as part of the design revision (#8). Implemented in the Capture and
Recorder issues. Related: [[decisions/2026-08-05-write-serialization]] ·
[[decisions/2026-08-05-failure-taxonomy]].
