---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [record-keeping, git-hooks, enforcement]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-record-keeping-design.md]
---

# Push gate forces distillation (native pre-push)

## Context
Session transcripts survive on disk even after auto-compact (230 MB transcript measured —
compact only compresses what is in-context). So what gets lost is not the original but the
**distillation**. Global-wiki measurement: even with the reminder appearing every session,
75 sessions (2.4 GB) piled up in a month with 0 ingests.
**Nudges alone do not produce distillation**, and distillation is cheapest while context is fresh.

## Options considered
Enforcement level — **A. nudge only** (empirically failed globally) · **B. block per commit**
(token cost; page pollution from repeatedly merging the same content) · **C. block per branch/push**
Mechanism — **M1. Claude PreToolUse deny** · **M2. git native pre-push**
Escape hatch — **ⓐ permissionDecision "ask"** (leaves no trace) · **ⓑ commit trailer** (auditable)

## Decision
**C + M2 + ⓑ.** `.githooks/pre-push` blocks the push when the pushed range contains no
`docs/llm-wiki/` change. The exception is a `Wiki-Skip: <reason>` trailer — the reason
stays in history as an audit trace.

## Rationale
M2 beats M1 on three axes: it **also catches direct terminal pushes**, it receives the
push range **exactly via stdin** rather than estimating from HEAD, and it is
**tool- and platform-neutral** rather than Claude Code-specific (GitHub/GitLab, any AI
tool). On block, stderr is visible to the model as a Bash tool result, so the
"block → /wiki-ingest → retry" flow works.

Fail-open principle: any git error or unexpected input passes. This gate is a **device
against unconscious omission, not a device against circumvention.**

## Accepted costs
- Cloner setup friction — one command, `git config core.hooksPath .githooks`
  (Claude Code installs it automatically via a SessionStart hook)
- The trailer can be abused — the machine checks existence only; abuse is caught by PR
  review and /wiki-lint
- Unconventional push forms (non-HEAD refspecs etc.) pass through — an intended limitation

## Outcome
_Update after implementation — the branch introducing this gate is itself the first real pass (dogfooding)._
