---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [ci, guards, documentation, drift, measurement]
created: 2026-08-06
updated: 2026-08-06
sources: [decisions/2026-08-05-ci-guard-scoping, concepts/assumption-vs-measurement]
---

# Every repository path a *maintained* document names must exist

Date: 2026-08-06 · Status: accepted · Issue: #47

Elaborates [[decisions/2026-08-05-ci-guard-scoping]] inside its own scope — a fourth guard,
built to that ADR's stated rule that a guard must refuse false positives even at the cost of
false negatives.

## Context

Fixing #47's text without a guard resets the clock. Two of the nine false claims were
mechanically detectable and had been wrong for weeks: `.claude/commands/` in the README's
structure block after the directory became `.claude/skills/`, and a `LICENSE` cited by three
documents that did not exist until #48. Both would have been caught the day they were written
by a check that asks whether a named path is there.

The other seven are semantic — "the README says MCP works and MCP does not" — and there is no
honest mechanical test for them. A keyword list that greps for `MCP` would fire on this
sentence.

## The two traps found before adoption

**The corpus is mostly dated records.** `docs/llm-wiki/raw/` is declared immutable by the wiki
schema — no edits, no deletions — and wiki pages, specs and plans record a state at a date.
A path renamed later makes those documents fire forever, and the only escapes are editing a
record we promised not to edit or switching the guard off. Both are worse than not checking
them at all.

**Tree diagrams are relative to something.** Measured across all 67 tracked Markdown files, an
unanchored scan of fenced blocks finds 190 path-shaped strings of which **121 do not exist —
64% false positives**. The cause is structural, not a regex defect: `development-rules.md`
draws `protocol/` and `message/` under a Java package root, `bootstrap.md` draws `attempts/`
under a problem directory, the record-repository template draws `problems/<lessonId>-<title>/`,
and the README deliberately drew `ps-records/`, which is a different repository. There is
non-path noise too (`application/json`). Shipping that guard means 121 wolf cries, and a guard
people learn to ignore is worse than no guard.

## Decision

The check lives in `scripts/guards.sh` (per [[decisions/2026-08-05-ci-guard-scoping]] point 4,
CI runs the same file a developer runs) and is narrow on three axes.

1. **Which documents.** Only those the project maintains as current: `README.md`,
   `CONTRIBUTING.md`, `CLAUDE.md`, `docs/*.md`. Dated records are excluded — the whole of
   `docs/llm-wiki/`, `docs/superpowers/`, `.harness/state/progress.md` — as is
   `template/ps-records/README.md`, which documents another repository.
2. **Which lines.** Markdown inline links, and structure blocks. Prose is never parsed, and
   links are read outside fences only — inside one the syntax is literal sample text.
3. **Which trees.** A fenced block is walked only when **its first line names a directory this
   repository actually has** (or `.`). That single rule disposes of every false positive above:
   `ps-records/`, `problems/<lessonId>-<title>/` and `com.brokenfinger.tracker/` all fail it and
   their subtrees are left alone. The README's block is anchored at `.` — which is what `tree`
   prints anyway — so it is walked in full.

Two supporting choices:

- **Existence is decided against git's index, not the working tree.** An untracked file present
  only on the author's machine does not satisfy the guard, so a dirty workspace cannot make it
  pass — the failure recorded in [[concepts/assumption-vs-measurement]], where a guard "passed"
  by reading stale local state.
- **`guard:planned` on a line opts that line out**, for a path that is deliberately not built
  yet. An inline marker rather than a file exclusion: the escape stays visible at the exact
  line it excuses, and cannot silently disable a whole document.

## Measurement

Under these rules, applied to **all 67** tracked Markdown files rather than just the six in
scope: **34 paths judged, 0 false positives** — against 121 for the unanchored version. The
guard was proved by making it fail rather than by watching it pass:

| Planted | Result |
|---|---|
| `.claude/commands/` restored in the README's structure block (the original #47 defect) | fires, `README.md:152 .claude/commands (structure block)` |
| `[MIT](LICENCE.md)` — the #48 defect in link form | fires, `README.md:200 LICENCE.md (link)` |
| `adapter/mcp/` in the structure block, marked `guard:planned` | passes, as intended |
| `~/ps-records/…`, an `https://` URL, `#anchor`, `/etc/passwd`, `problems/<id>/README.md` | none fire |

Verified from a **fresh `git clone` of the branch**, not from the working tree, for the reason
[[concepts/assumption-vs-measurement]] gives: a guard must be tested from the state it is meant
to protect.

## Accepted costs

- **The semantic half is not covered and is not faked.** "This sentence is no longer true"
  stays a review responsibility, which is the argument for
  [[decisions/2026-08-06-one-place-carries-tense]] — one place to keep true instead of nine.
- **A mistyped tree root silently disables its block.** `docs/lm-wiki/` anchors nothing and the
  subtree goes unchecked. Deliberate: the alternative is guessing what a root meant, which is
  where the 121 came from.
- **Indented listings without `├──`/`└──` glyphs are not read at all**, so the fixture listing
  in `development-rules.md` is unguarded.
- **A branch that adds a file and documents it must `git add` before the guard passes.** The
  index-based check is what closes the dirty-workspace hole, and this is its price.
- Dated records keep whatever broken paths they contain. One is known: a `](CONTRIBUTING.md)`
  inside a fenced sample block in `docs/superpowers/plans/2026-08-04-oss-workflow.md` — it is
  quoted README text, correct relative to the destination, and reads as broken only to a
  scanner that ignores fences. Left alone.

## Outcome

Recorded 2026-08-06 with #47. Related: [[decisions/2026-08-05-ci-guard-scoping]] ·
[[decisions/2026-08-06-one-place-carries-tense]] · [[concepts/assumption-vs-measurement]].
