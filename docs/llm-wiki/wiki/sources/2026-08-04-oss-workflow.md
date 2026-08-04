---
type: source
project: programmers-tracker
tags: [open-source, workflow, language]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-oss-workflow.md]
---

# 2026-08-04 OSS workflow session summary

## Key claims
1. GitHub issues and PRs share one number sequence — branch names must use the returned issue number, never an assumed one.
2. Server-side branch protection is the only enforcement that binds outside contributors and the owner equally; `--dry-run` cannot negative-test it.
3. GitHub issue-form YAML must be block-style — flow mappings break on prose commas/`?`.
4. Full English migration is a functional requirement of going open-source (gate stderr is read by strangers), not cosmetics.
5. An injected dispatch can sit unsubmitted in a worker's composer — 0 tokens consumed is the tell; a bare Enter recovers it.

## Pages this source updated
[[decisions/2026-08-04-english-only-artifacts]] ·
[[decisions/2026-08-04-issue-first-squash-flow]]
