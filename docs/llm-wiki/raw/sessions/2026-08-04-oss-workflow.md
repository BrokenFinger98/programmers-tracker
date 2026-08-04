# 2026-08-04 OSS workflow session (english migration + dev flow)

> Curated raw record — the distillation of the afternoon arc that followed the
> record-keeping design. Originals live in the personal global archive.

## Context

Right after PR #1 (record-keeping) merged, the owner set the open-source
posture: issue-first development, GitHub-adapted skills, community docs modeled
on openclaw, CI — then mid-arc widened the scope to a full English migration
("this is open source; everything should be English") and deferred CI to
Phase 1.

## Verified facts

1. **openclaw reference** (385k★): CONTRIBUTING routes features to issues first
   ("most features are not accepted"), welcomes AI-assisted PRs with disclosure,
   caps open PRs per author; `.github/` carries issue forms + PR template.
   Adopted scaled-down; CODEOWNERS/dependabot/CodeQL rejected as below scale.
2. **GitHub issues and PRs share one number sequence** — merged PR #1 consumed
   `1`, so the first issue became #2. Branch names must use returned numbers.
3. **Branch protection works as specified**: real push to main returned
   `remote rejected (protected branch hook declined — Changes must be made
   through a pull request)`; `--dry-run` does NOT exercise server-side
   protection (nothing is sent), so negative tests need a real rejected push.
4. **GitHub issue-form YAML breaks with flow mappings** when prose contains
   commas or `?` — `attributes: { label: X, description: sentence, with commas }`
   splits at the comma. Block style only. (Planning defect; caught in
   coordinator review, plan synced.)
5. **The owner's `block-danger.sh` PreToolUse hook blocks `git reset --hard`**
   even for a verified-safe reconcile after a squash merge — the human ran the
   one-liner. Working as designed.
6. **Orchestration operational notes**: an injected dispatch can land in the
   worker's composer without the Enter registering (worker sat at 0 tokens
   until the coordinator sent a bare Enter); zsh arrays are 1-based, which
   shifted a task/terminal pairing loop by one (harmless — the injected spec,
   not the terminal title, defines the work).

## Decisions made

[[decisions/2026-08-04-english-only-artifacts]] ·
[[decisions/2026-08-04-issue-first-squash-flow]]

## Discarded / corrected along the way (schema §5.2)

- **CI now** — deferred to Phase 1 by the owner (no Kotlin code exists yet to
  compile or test; protection gains required status checks when CI lands).
- **Korean-first documentation (rules §12)** — reversed by the owner for the
  open-source posture; the reversal is the english-only ADR.
- **"settings.json is `{}`"** — planning-time misread (only the `hooks` key was
  queried); the file held 9 permission entries. Caught by the worker before a
  full-replace would have deleted them; merge chosen.

## Execution record

Two issues, two squash-merged PRs, in order: #2 → PR #3 (translation,
6 parallel workers over disjoint clusters, ~4,400 lines / 37 files;
FENCES-IDENTICAL on the protocol doc, wikilink check clean, gate E2E
PASS=7 FAIL=0 rerun on the translated hook) and #4 → PR #5 (skills ×3,
constitution enforcement, community docs, repo settings). The flow's own PR
was its first end-to-end run.
