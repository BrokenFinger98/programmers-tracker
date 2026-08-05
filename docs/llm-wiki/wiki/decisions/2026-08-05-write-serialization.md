---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [concurrency, storage, git, idempotency, single-writer]
created: 2026-08-05
updated: 2026-08-05
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md]
---

# Write serialization: confined single writer, JSONL as the attempt authority

Date: 2026-08-05 · Status: accepted · Issue: #8

## Context

The design allows up to 8 concurrent subscriptions (design §4.1) whose gradings can
complete at the same time, and states no lock, no single-writer rule and no ordering
rule anywhere. Three shared write targets are exposed:

- `log/submissions.jsonl` — append per grading
- `.ps/timers.json`, `.ps/hints.json` — **read-modify-write**, so a lost update is worse
  than interleaved appends
- the git repository — one index, and `git commit` fails outright when `index.lock` is held

A fourth hazard is undefined rather than concurrent: the design never says where the
`attempts/NNN` number comes from — not a directory scan, not the JSONL, not a counter.
With no defined source there is both a race and a reconciliation ambiguity.

## Options considered

- **Do nothing (assume a single writer exists)** — false. Nothing in the design creates
  that property; it has to be built.
- **Actor / in-memory `Channel` with one consumer** — serializes correctly, but the
  buffer dies with the process (and losing a grading is unrecoverable, protocol §11),
  errors detach from the producing session, shutdown needs an explicit drain protocol,
  and a bounded channel back-pressures the socket read loop, stalling *all* channels.
- **Global `Mutex` around the write section** — fine, but must not be held on the read
  loop for the same head-of-line reason; equivalent to the chosen option once sessions
  run on their own coroutines.
- **Per-problem locks** — finer granularity buys nothing at human event rates and git
  still needs a global section.
- **File locks (`FileChannel.lock`)** — solves the wrong problem: in-process it is
  redundant, cross-process it is too coarse for our layout.

## Decision

1. **Serialize by dispatcher confinement.** Derived writes run inside
   `withContext(writerDispatcher)` where the writer is a single-parallelism dispatcher.
   Session assembly runs on per-session coroutines, so the socket read loop is never
   blocked. No consumer coroutine, no drain protocol, errors propagate to the session
   that caused them. Durability is already handled upstream by the raw log
   ([[decisions/2026-08-05-capture-pipeline-stages]]).
2. **`log/submissions.jsonl` is the single authority for `attempt`.** The number is
   restored into an in-memory per-problem counter at startup and allocated inside the
   writer; `attempts/NNN.*` filenames are derived from it. No directory scan — which
   removes the read-then-write race instead of guarding it.
3. **Read-modify-write state uses temp-file + atomic replace**; readers must tolerate a
   torn final JSONL line (lenient line parsing, same posture as protocol parsing).
4. **Git is a separate, retryable reconciliation step**, not a step inside the write
   path: "commit whatever is uncommitted" is idempotent, backs off when `index.lock` is
   held by an external process (IntelliJ, a terminal, an Obsidian plugin), and its
   failure never fails a capture. Staging is path-scoped.
5. **The record repository is locked exclusively at startup.** In-process serialization
   says nothing about a second process, and the Docker decision makes "container plus a
   local run" a realistic double-writer.
6. **Records carry a capture key** derived from the terminal frame, because Programmers
   supplies no submission id (protocol §11) and `(lessonId, action, attempt)` is not
   unique for `run` (design §5.1 — runs keep the previous submit's number). The writer
   drops duplicates, which a re-subscribe after reconnect or a heartbeat re-`POST` can
   otherwise produce.

## Rationale

- Structural concurrency removal beats concurrency control: decisions 2 and 5 delete
  shared mutable state rather than guarding it, and the remaining serialization is one
  primitive rather than a lock hierarchy.
- Confinement keeps failure attribution intact, which matters because a failed write is
  the one event that must be diagnosable — silent wrong data is the project's stated
  worst outcome (CLAUDE.md).
- Decision 4 follows from the observation that git's lock is not ours to own; the only
  robust posture toward an external lock holder is retry, and the only safe coupling to
  capture is none.

## Accepted costs

- A single writer serializes everything globally; if event rates ever rise this becomes
  a bottleneck by construction. Accepted because the rate is bounded by one human
  solving problems.
- The startup counter restore must read the JSONL, so a corrupt log degrades attempt
  numbering — mitigated by lenient parsing, but it is a real coupling.
- The exclusive repo lock means a second instance refuses to start rather than
  degrading, which will be surprising the first time it happens in development.
- Capture keys are our invention, not an upstream identity, so dedup is heuristic at the
  edges (e.g. a genuine identical resubmission, which protocol §13.2 measured returns a
  cached result within 1 s).

## Outcome

Recorded 2026-08-05 as part of the design revision (#8). Related:
[[decisions/2026-08-05-capture-pipeline-stages]] · [[decisions/2026-08-05-failure-taxonomy]].
