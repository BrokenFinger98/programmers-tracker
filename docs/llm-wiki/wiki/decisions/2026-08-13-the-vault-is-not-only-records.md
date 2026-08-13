---
type: decision
project: programmers-tracker
tags: [git, vault, obsidian, privacy]
author: BrokenFinger98
created: 2026-08-13
updated: 2026-08-13
sources: [raw/sessions/2026-08-13-the-tally-that-counted-runs.md]
---

# The record repository ignores what is not a record, whoever wrote it

## Context

Reconciliation is `git add --all` — deliberately, because its job is catching records a crash
left behind, and a scope narrow enough to miss one would defeat it
([[decisions/2026-08-05-write-serialization]]).

The vault is not only records. Once the owner started opening it in Obsidian (#229), the editor's
own state started arriving in commits. Measured on 2026-08-12:

```
$ git log --format=%h -- .obsidian | wc -l
4

$ git show --stat 6e0b3ff
chore: reconcile uncommitted records
 .obsidian/graph.json | 2 +-
```

That commit is the **23:00 daily backup**, and it contains no records at all — one line of graph
zoom, under a message that says otherwise.

## Options considered

**A. Scope the staging to what the server writes** — `log/`, `problems/`, `tags/`, `.gitignore`,
all named by `RecordLayout`. Honest by construction.
*Rejected.* If the server ever writes outside that list, reconciliation stops catching it and a
record goes uncommitted — the direction CLAUDE.md ranks worst. The net exists because the tidy
path already missed something once; trading a visible annoyance for an invisible loss is the
wrong way round.

**B. Ignore `.obsidian/`.** One rule, complete by construction, no maintenance. Reconciliation
keeps its full scope because ignored files are never staged.

**C. Leave it.** It is the user's repository and the noise is small.
*Rejected* — it recurs every day now that the vault is worth opening, and a commit message that
says "records" while carrying none is the same silent-wrong-data family the constitution is
organised against.

## Decision

**B.** `RecordRepositoryIgnores` grows from one rule to two, and the shipped template carries
both.

**A whole directory, never a list of files inside it.** The rule used to name `.ps/session`,
`.ps/cookies*` and `.ps/catalog.json` one at a time, so every state file added afterwards was
committed by default — and one of them was a credential (#122). Obsidian adds files on its own
schedule; a list would be wrong the first time it did.

## Rationale

Writing an ignore rule for **someone else's tool** into the user's `.gitignore` is a different act
from ignoring our own `.ps/`, and it is justified by who is doing the committing: the server is.
A tool that commits on your behalf owes you a say in what it commits, and the rule ships with the
sentence that says how to undo it — *delete this line if you would rather version your vault's
settings.*

## Accepted costs

- **The vault's graph and appearance settings no longer travel with a clone.** Obsidian
  regenerates them; nothing this tool owns is lost.
- **It does nothing for a repository that already tracks those files.** Ignoring is not
  untracking, and `git rm --cached` rewrites what a user has already published. That is theirs to
  run, and the PR says so rather than the server doing it quietly.
- **`reconcile` still commits anything else the user leaves in the vault** — an unfinished note, a
  dropped screenshot. This closes the instance that recurs daily, not the shape. Option A remains
  the only fix for the shape, and its risk has not changed.

## Outcome

Implemented in #234. Two rules, each appended at most once and never re-added in whichever
spelling the user already used.
