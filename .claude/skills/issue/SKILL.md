---
name: issue
description: Create a GitHub issue, then a branch <type>/<issue#>-<slug> from main, and check it out. Use when starting any new work — the constitution forbids working without an issue.
---

# Create Issue and Branch (GitHub)

Every change starts here. Flow: issue → branch → work → /commit → /pull-request → squash merge.

## Process

1. Ask issue type (one question)
2. Ask title, then description (one at a time)
3. Organize into the type's template; show preview; confirm (Yes/Edit/Cancel)
4. `gh issue create` with the mapped label
5. Branch `<type>/<issue#>-<slug>` from fresh main; checkout

## Types

| Type | Title prefix | Branch prefix | Label |
|---|---|---|---|
| Feature | `[Feature]` | `feat/` | `enhancement` |
| Bug | `[Bug]` | `fix/` | `bug` |
| Refactor | `[Refactor]` | `refactor/` | `refactor` |
| Docs | `[Docs]` | `docs/` | `documentation` |
| Test | `[Test]` | `test/` | `test` |
| Chore | `[Chore]` | `chore/` | `chore` |

## Description templates

Feature: `## Overview` · `## Requirements` · `## Done when` (checkboxes)
Bug: `## Symptom` · `## Reproduce` · `## Suspected cause` · `## Done when`
Refactor: `## Overview` · `## Changes` · `## Done when (tests pass, no behavior change)`
Docs/Test/Chore: `## Overview` · `## Work items`

For protocol-touching work, "Done when" must include a measured-verification item
(this repo's constitution: implemented ≠ verified against Programmers).

## Execution

```bash
gh issue create --title "[Type] <title>" --label <label> --body "<organized body>"
# parse the issue number <n> from the URL in the output
git checkout main && git pull origin main
git checkout -b "<branch-prefix><n>-<kebab-slug>"
```

Slug: 2–4 lowercase ascii words from the title, kebab-case (e.g. `feat/12-actioncable-client`).
No `#` in branch names.

## Confirm

Show: issue number + URL, branch name, and the next step (`work → /commit`).
