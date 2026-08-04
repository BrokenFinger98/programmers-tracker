---
name: commit
description: Auto-analyze staged changes and commit with a conventional message. No user input needed except final confirmation. English-only messages, no AI attribution.
---

# Auto Commit (project)

## Rules

- **No AI attribution** — never add Co-Authored-By or any Claude/AI trailer
- **English only** — message, body, everything
- **Confirm before commit** — always preview first (skip only with `--quick`)
- Conventional Commits: `<type>(<scope>): <subject>` — types
  feat|fix|docs|style|refactor|test|chore|perf|ci|revert; imperative, lowercase,
  ≤50 chars, no trailing period; body explains what/why at ≤72 cols

## Project gates (checked before preview)

1. **Protocol-related change?** (touches `protocol` package, parser, fixtures, or
   `docs/programmers-protocol.md`) → the body MUST cite measured evidence or a
   protocol-doc section. Refuse to commit without it (constitution §Forbidden).
2. **New production `.kt`?** → its test pair must be staged in the same PR scope;
   warn loudly if missing (TDD pairing gate).
3. **Docs-only branch heading to push without wiki changes?** → remind that the
   push gate will ask for /wiki-ingest or a `Wiki-Skip:` trailer.

## Process

1. `git diff --cached --name-only` — if empty, tell the user to stage first and stop
2. Run /review on staged files (skip with `--skip-review`); surface critical issues
   and ask before continuing
3. Analyze `git diff --cached` + recent log; derive type/scope/subject
4. Apply project gates above
5. Preview the full message; Yes/Edit/Cancel
6. `git commit -m "<message>"` — never `--author`, never AI trailers

## Options

| Option | Effect |
|---|---|
| `--skip-review` | Skip the /review step |
| `--quick` | Skip review + commit without confirmation |
