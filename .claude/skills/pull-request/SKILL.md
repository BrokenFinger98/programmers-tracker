---
name: pull-request
description: Push the branch, create a rich GitHub PR (auto-generated body with a mermaid diagram), and drive it to squash merge + branch delete. Use when the branch is ready.
---

# Pull Request (GitHub, squash-only)

## Rules

- **No user input** for content — analyze branch name, commits, and diff
- **Target is always `main`**; merge is always **squash + delete branch**
- **Preview before create**; confirm with the user
- Body ends with `Closes #<n>` — issue number parsed from the branch
  (`<type>/<n>-<slug>`)

## Pre-flight (in order)

1. On a work branch (not main) with commits ahead of `origin/main`
2. `.harness/state/progress.md` updated in this branch (constitution gate) —
   if not, stop and update it first
3. Push: `git push -u origin $(git branch --show-current)`
   - **wiki gate blocks?** The branch made decisions without recording them.
     Offer: run /wiki-ingest, or (only for genuinely record-free branches)
     `git commit --amend --no-edit --trailer 'Wiki-Skip: <reason>'` and re-push

## Body generation

Analyze `git log origin/main..HEAD` and `git diff origin/main..HEAD --stat`.
Pick ONE diagram that clarifies the change:

| Change | Diagram |
|---|---|
| New/changed API flow | sequence |
| Class/type structure | classDiagram |
| Business logic branches | flowchart TD |
| Module/layer dependencies | graph |

Mermaid safety: alphanumeric node IDs, labels quoted if they contain
special chars, ≤10 nodes, ≤2 subgraphs, all nodes connected.

Template:

    ## Summary
    <one line>

    ## Changes
    - <bullet per logical change>

    <diagram section when it adds clarity — omit for trivial diffs>

    ## Test
    - [ ] <what was run and the result — cite real output, not intentions>

    Closes #<n>

## Create · merge

```bash
gh pr create --title "<type>: <subject> (#<n>)" --body "<generated>"
# after review (and CI when it exists):
gh pr merge <pr> --squash --delete-branch
git checkout main && git pull origin main
```

Post-merge, confirm: branch deleted (local+remote), main fast-forwarded, and
suggest /issue for the next piece of work.

## Options

| Option | Effect |
|---|---|
| `--draft` | Create as draft |
| `--no-diagram` | Skip the mermaid section |
