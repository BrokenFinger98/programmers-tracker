# OSS Workflow Implementation Plan (english-migration + dev-workflow)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Translate every work artifact to English, then install the issue-first squash-merge dev flow (project skills, CLAUDE.md enforcement, community docs, repo settings).

**Architecture:** Two issues, two PRs, strictly ordered — PR A (translation) rewrites existing content in place; PR B (workflow) adds English-native content on top. Translation fans out to 6 parallel workers over disjoint file clusters; the coordinator owns all git operations.

**Tech Stack:** gh CLI · git · Claude Code project skills (.claude/skills) · GitHub issue forms

**Spec:** [`docs/superpowers/specs/2026-08-04-oss-workflow-design.md`](../specs/2026-08-04-oss-workflow-design.md)

**Facts settled at planning time (do not re-derive):**
- Translation surface: ~4,400 lines / 37 files (inventory in §Clusters below)
- Issue and PR numbers share one sequence; merged PR #1 took `1` — use the numbers `gh` actually returns
- Local `main` is reset to `origin/main` (0925871); `feat/record-keeping` deleted
- `.editorconfig` / `.gitattributes` contain no Korean — out of scope
- `goal.md` is gitignored: translate the local file, but it will not appear in the PR
- Branch protection does NOT exist during PR A — explicit `--delete-branch` needed on its merge

---

## Part 1 — English migration (PR A)

### Task A0: File the issue and create the branch (manual dogfood — skills don't exist yet)

- [ ] **Step 1: Issue**

```bash
gh issue create \
  --title "[Docs] Migrate all work artifacts to English" \
  --label documentation \
  --body "## Overview
The repo is public and must assume external contributors. All work artifacts move to English.

## Scope
- Constitution (CLAUDE.md), development rules (§12 reversed: English-only), README
- Protocol doc (translate-only: code fences and measured data stay byte-identical)
- Specs, plans, llm-wiki pages (schema, index, ADRs, concepts, entities, syntheses, sources)
- Hook comments AND user-facing hook stderr; wiki skills; state files; template/

## Kept as-is
- docs/llm-wiki/raw/sessions/* and existing log.md entries (schema: raw is immutable)

## Done when
- [ ] Korean remains only in the declared keep-list
- [ ] Protocol code fences byte-identical to main
- [ ] Wiki gate E2E still green after hook message translation
- [ ] New ADR records the §12 reversal"
```

Note the returned number as `<n>`.

- [ ] **Step 2: Branch**

```bash
git checkout main && git pull origin main && git checkout -b "docs/<n>-english-migration"
```

- [ ] **Step 3: Commit the (already-English) spec and this plan**

```bash
git add docs/superpowers/specs/2026-08-04-oss-workflow-design.md docs/superpowers/plans/2026-08-04-oss-workflow.md
git commit -m "docs(spec): oss workflow — english migration, dev flow, community docs

Two-PR workstream: translate everything first, then add the issue-first
squash-only flow in English. CI deferred to Phase 1 by user decision.
Supersedes development-rules §12 (Korean-first) — reversal recorded as
an ADR in this branch."
```

### Clusters (6 translation workers, disjoint files, ~4,400 lines)

| W | Files | Lines |
|---|---|---|
| W1 | `docs/programmers-protocol.md` | 425 |
| W2 | `docs/superpowers/specs/2026-08-04-programmers-tracker-design.md` | 985 |
| W3 | `CLAUDE.md` · `docs/development-rules.md` (§12 reversal) · `README.md` · `.gitignore` comments | 699 |
| W4 | `docs/llm-wiki/`: CLAUDE.md, index.md, README.md, log.md header, `wiki/**` (17 pages) + **new ADR** english-only-artifacts + index/log registration | ~910 |
| W5 | `docs/superpowers/plans/2026-08-04-record-keeping.md` | 918 |
| W6 | `docs/superpowers/specs/2026-08-04-record-keeping-design.md` · `.githooks/pre-push` · `.claude/hooks/inject-state.sh` · `.claude/skills/wiki-{ingest,lint,query}/SKILL.md` · `.harness/state/{goal,progress}.md` · `template/ps-records/{README.md,.gitignore}` | ~415 |

### Shared worker rules (include verbatim in every worker prompt)

```
TRANSLATION RULES — deviations are defects:
1. Translate prose to natural English. NO semantic edits, NO restructuring,
   NO reordering, NO "improvements". Same headings hierarchy, same tables,
   same lists, same emphasis.
2. Markdown links: translate link TEXT, never link TARGETS. [[wikilinks]]
   targets and all file paths stay exactly as-is (files are not renamed).
3. Frontmatter: keys, dates, project, sources unchanged. Translate only
   Korean tag values and prose fields.
4. Code fences: translate comments and echoed/user-facing strings ONLY in
   shell/config examples. In MEASURED DATA (captured JSON, protocol
   messages, fixture content) change NOTHING — Korean strings inside
   measured data are evidence, not prose.
5. docs/programmers-protocol.md only (W1): ALL code fences byte-identical.
   Zero changes inside fences, comments included.
6. Keep literals: `Wiki-Skip:`, `/wiki-ingest`, `/wiki-lint`, `/wiki-query`,
   command names, paths, env var names.
7. Files only — you MUST NOT run any git command (add/commit/checkout/...).
   The coordinator owns git. Do not touch files outside your cluster.
8. Report per file: lines translated, anything you were unsure about.
```

### Task A1: Dispatch W1–W6 in parallel (same worktree, disjoint clusters)

- [ ] **Step 1**: Orchestration — one Run, 6 tasks, 6 fresh agent terminals in the
  active worktree, dispatch all before waiting (rules above + cluster file list in
  each spec). Cluster-specific additions:
  - **W3**: use this fixed heading glossary (PR B anchors depend on it):
    `⚡ 세션 시작 시 — 사용자에게 답하기 전에` → `⚡ At Session Start — before answering the user` ·
    `이 저장소의 파일 지도` → `File Map` · `Architecture (불변 결정)` → `Architecture (immutable decisions)` ·
    `Forbidden (자동 거부)` → `Forbidden (auto-reject)` (subsections: Protocol / Security · Privacy / Feature scope / Development) ·
    `State 파일 운영` → `State File Operations` · `보고 형식` → `Report Format` ·
    `코딩 컨벤션` → `Coding Conventions` · rules `11. 커밋 · 브랜치` → `11. Commits · Branches` ·
    `12. 문서` → `12. Documentation`.
    §12 body becomes: "All committed artifacts are written in English — docs,
    comments, commit messages, wiki pages, user-facing tool output. Rationale and
    accepted costs: see the ADR [[decisions/2026-08-04-english-only-artifacts]].
    (Reversed 2026-08-04; was Korean-first for Korean job-seekers.)"
  - **W4**: also create `docs/llm-wiki/wiki/decisions/2026-08-04-english-only-artifacts.md`:

```markdown
---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [record-keeping, language, open-source]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-record-keeping-design.md]
---

# All work artifacts in English

## Context
development-rules §12 said "write in Korean — the audience is Korean job-seekers."
The repo went public with external issues/PRs expected; Korean-only docs,
hook messages, and templates exclude every non-Korean contributor.

## Options considered
- **A. Korean-first, English on demand** — original §12
- **B. Bilingual docs** — every page twice; guaranteed drift (same failure
  mode as the retired decisions.md duplication)
- **C. English-only artifacts** — conversation language stays per-user

## Decision
**C.** Everything committed is English: docs, comments, commit messages,
wiki pages, user-facing hook output.

## Rationale
Open-source posture makes contributor-facing language a functional
requirement — the push gate's stderr is read by strangers. B recreates
the two-copies-diverge failure this project already paid for once.

## Accepted costs
- Korean readers (the original audience) lose first-language docs
- Mixed-language history: `raw/sessions/` and old `log.md` entries stay
  Korean — the wiki schema declares raw immutable, and history is evidence
- One-time ~4,400-line translation with drift risk on the protocol doc,
  mitigated by fence-byte-stability checks

## Outcome
_Update after the migration PR merges._
```

    Register in `index.md` Decisions section (append after the last entry):
    `- 2026-08-04 [[decisions/2026-08-04-english-only-artifacts]] — All work artifacts in English`
    Append to `log.md`:
    `## [2026-08-04] ingest | English-only artifacts decision → 1 created (ADR), translation pass over all wiki pages`
  - **W6**: `.githooks/pre-push` stderr becomes (keep structure/exit codes identical):
    pass → `wiki-gate: pass — docs/llm-wiki/ changes included. Consider /wiki-lint before the PR.`
    trailer → `wiki-gate: skipped by trailer — Wiki-Skip: <reason>`
    fail-open → `wiki-gate: skip (rev-list failed — fail-open)`
    block → `✖ wiki-gate: no docs/llm-wiki/ change in the pushed range (<total> commits).` /
    `  Record this branch's decisions and know-how first: /wiki-ingest` /
    `  If there is truly nothing to record, leave an auditable reason:` /
    `    git commit --amend --no-edit --trailer 'Wiki-Skip: <reason>'`
    `inject-state.sh` injected header becomes:
    `[out-of-session memory injection — session start/compact recovery. Scan the Decisions section of the index for conflicts with existing decisions]`

- [ ] **Step 2**: As each worker finishes, coordinator reviews its diff
  (`git diff -- <cluster files>`) and commits that cluster:

```bash
# per cluster, e.g. W3:
git add CLAUDE.md docs/development-rules.md README.md .gitignore
git commit -m "docs: translate constitution, rules, readme to English

Part of the english-only migration. §12 reversed (English-only) per ADR
2026-08-04-english-only-artifacts; heading glossary fixed for downstream
anchors."
# analogous commits: W1 "docs(protocol): translate prose, fences untouched"
# W2 "docs(spec): translate tracker design"  W4 "docs(wiki): translate pages, add english-only ADR"
# W5 "docs(plan): translate record-keeping plan"  W6 "chore: translate hooks, wiki skills, state, template"
```

### Task A2: Coordinator verification

- [ ] **Step 1: Korean sweep** — only the keep-list may remain

```bash
grep -rln "[가-힣]" --include="*.md" --include="*.sh" --include="*.yml" \
  --include="pre-push" --include=".gitignore" . | grep -v "^\.git/" | sort
```

Expected output, exactly:
```
docs/llm-wiki/log.md                     (old entry lines only — verify by eye)
docs/llm-wiki/raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md
docs/llm-wiki/raw/sessions/2026-08-04-record-keeping-design.md
docs/programmers-protocol.md             (inside fences only)
docs/superpowers/specs/2026-08-04-programmers-tracker-design.md   (inside fences only, if any)
docs/superpowers/plans/2026-08-04-record-keeping.md               (quoted historical worker output, if any)
```
Any other file → route back to its cluster worker.

- [ ] **Step 2: Protocol fence byte-stability**

```bash
extract() { awk '/^```/{f=!f;next} f' "$1"; }
git show main:docs/programmers-protocol.md > /tmp/proto-main.md 2>/dev/null \
  || git show origin/main:docs/programmers-protocol.md > /tmp/proto-main.md
extract /tmp/proto-main.md > /tmp/fences-main.txt
extract docs/programmers-protocol.md > /tmp/fences-work.txt
diff /tmp/fences-main.txt /tmp/fences-work.txt && echo FENCES-IDENTICAL
```

Expected: `FENCES-IDENTICAL`

- [ ] **Step 3: Wikilink integrity**

```bash
grep -rho "\[\[[^]]*\]\]" docs/llm-wiki/wiki docs/llm-wiki/index.md | sort -u \
 | sed 's/\[\[//;s/\]\]//;s/|.*//' | while read -r t; do
   [ -f "docs/llm-wiki/wiki/${t}.md" ] || echo "BROKEN: $t"
 done; echo LINK-CHECK-DONE
```

Expected: only `LINK-CHECK-DONE` (no BROKEN lines).

- [ ] **Step 4: Gate E2E after message translation** — save the harness from the
  (now-translated) record-keeping plan Task 1 block to the scratchpad, then:

```bash
bash <scratchpad>/e2e-wiki-gate.sh "$(pwd)/.githooks/pre-push"
```

Expected: `PASS=7 FAIL=0` (assertions check exit codes, not message text).

- [ ] **Step 5: inject-state smoke**

```bash
bash .claude/hooks/inject-state.sh </dev/null | jq -r '.hookSpecificOutput.additionalContext' | head -2
```

Expected: English header line + `=== .harness/state/goal.md ===`.

### Task A3: PR A

- [ ] **Step 1: Push (gate: W4 wiki changes satisfy it) and create the PR**

```bash
git push -u origin "docs/<n>-english-migration"
gh pr create --title "docs: migrate all work artifacts to English" --body "## Summary
Full English migration of work artifacts — constitution, rules, README, protocol doc, specs, plans, llm-wiki, hook comments and user-facing hook messages, wiki skills, state, template.

## Kept as-is (declared exceptions)
raw/sessions/* and existing log.md entries (immutable history); Korean strings inside measured protocol data.

## Verification
- Korean sweep matches the declared keep-list exactly
- Protocol code fences byte-identical to main (FENCES-IDENTICAL)
- Wiki-gate E2E PASS=7 FAIL=0 after stderr translation; inject-state smoke OK
- Wikilink integrity check clean

Reversal of development-rules §12 recorded in ADR \`2026-08-04-english-only-artifacts\`.

Closes #<n>

🤖 Generated with [Claude Code](https://claude.com/claude-code)"
```

- [ ] **Step 2: After user review — squash-merge with branch delete** (protection/auto-delete not installed yet)

```bash
gh pr merge <pr-number> --squash --delete-branch
git checkout main && git pull origin main
```

---

## Part 2 — Dev workflow (PR B; starts only after PR A merges)

### Task B0: Issue + branch

- [ ] **Step 1**

```bash
gh issue create \
  --title "[Chore] Install issue-first squash-merge dev flow" \
  --label chore \
  --body "## Overview
Adopt the standard flow for all future work: issue → branch <type>/<issue#>-<slug> → PR → squash merge + delete branch. Applies to external contributors too.

## Scope
- Project skills: /issue, /commit, /pull-request (GitHub-adapted from the owner's GitLab skills)
- CLAUDE.md: mandatory flow section + Forbidden entries (no direct main commits, English-only artifacts)
- development-rules §11: branch naming, chore/ prefix
- CONTRIBUTING.md, issue forms (bug/feature), PR template
- Repo settings: squash-only, auto-delete branches, main branch protection (PR required, admins included)

## Done when
- [ ] /issue /commit /pull-request resolve to project versions in-repo
- [ ] gh api GET confirms merge methods + protection
- [ ] This PR itself lands via the new flow end to end"
```

Label `chore` may not exist yet — Task B4 creates it; if this command fails on the label, create labels first (B4 Step 1) and re-run.
Note the number as `<m>`.

- [ ] **Step 2**

```bash
git checkout main && git pull origin main && git checkout -b "chore/<m>-dev-workflow"
```

### Task B1: `/issue` skill

**Files:** Create `.claude/skills/issue/SKILL.md`:

```markdown
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
```

- [ ] **Step: verify frontmatter parses** — `head -5 .claude/skills/issue/SKILL.md` shows the yaml block.

### Task B2: `/commit` skill

**Files:** Create `.claude/skills/commit/SKILL.md`:

```markdown
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
```

### Task B3: `/pull-request` skill

**Files:** Create `.claude/skills/pull-request/SKILL.md`:

```markdown
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
```

### Task B4: Labels + repo settings (one-time, effective immediately)

- [ ] **Step 1: Labels**

```bash
gh label create refactor --color "D4C5F9" --description "Code improvement, no behavior change" 2>/dev/null || true
gh label create test     --color "0E8A16" --description "Test code" 2>/dev/null || true
gh label create chore    --color "FEF2C0" --description "Build, config, tooling" 2>/dev/null || true
gh label list
```

Expected: list includes bug, documentation, enhancement, refactor, test, chore.

- [ ] **Step 2: Merge methods + auto-delete**

```bash
gh api -X PATCH repos/BrokenFinger98/programmers-tracker \
  -F allow_squash_merge=true -F allow_merge_commit=false \
  -F allow_rebase_merge=false -F delete_branch_on_merge=true \
  --jq '{squash: .allow_squash_merge, merge: .allow_merge_commit, rebase: .allow_rebase_merge, autodelete: .delete_branch_on_merge}'
```

Expected: `{"squash":true,"merge":false,"rebase":false,"autodelete":true}`

- [ ] **Step 3: Branch protection on main** (PR required; admins included; status checks null until CI exists — Phase 1)

```bash
cat > /tmp/protection.json <<'EOF'
{
  "required_status_checks": null,
  "enforce_admins": true,
  "required_pull_request_reviews": { "required_approving_review_count": 0 },
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false
}
EOF
gh api -X PUT repos/BrokenFinger98/programmers-tracker/branches/main/protection \
  --input /tmp/protection.json \
  --jq '{admins: .enforce_admins.enabled, reviews: .required_pull_request_reviews.required_approving_review_count}'
```

Expected: `{"admins":true,"reviews":0}`

- [ ] **Step 4: Negative test** — direct push to main must now fail:

```bash
git push origin HEAD:main --dry-run 2>&1 | tail -2
```

Expected: rejection mentioning protected branch (wiki gate may fire first locally — either rejection is a pass; note which one fired).

### Task B5: CLAUDE.md + development-rules enforcement (anchors = W3 glossary)

- [ ] **Step 1: CLAUDE.md — new section after Quality Gate**

```markdown
## Development Flow (mandatory)

Every change — including docs — follows:

```
/issue  →  <type>/<issue#>-<slug>  →  work  →  /commit  →  /pull-request  →  squash merge (branch auto-deleted)
```

- No work without an issue; no commits directly on `main` (server-enforced)
- Branch types: `feat/` `fix/` `docs/` `refactor/` `test/` `chore/`
- Use the project skills `/issue`, `/commit`, `/pull-request` — they encode this
  repo's gates (measured evidence for protocol changes, TDD pairing, wiki gate)
- All committed artifacts are English (see development-rules §12 and the ADR
  `2026-08-04-english-only-artifacts`)
```

- [ ] **Step 2: Forbidden (auto-reject) — add under the Development subsection**

```markdown
- ❌ **Commits or pushes directly to `main`** — everything goes through an issue
  and a squash-merged PR (branch protection enforces this server-side)
- ❌ **Non-English committed artifacts** — docs, comments, commit messages, wiki
  pages, user-facing tool output
```

- [ ] **Step 3: development-rules §11 (Commits · Branches) — replace the branch line with**

```markdown
- Branches: `<type>/<issue#>-<slug>` — types `feat/` · `fix/` · `docs/` ·
  `refactor/` · `test/` · `chore/` (e.g. `feat/12-actioncable-client`).
  Created by `/issue` from a fresh `main`; no `#` in branch names.
- PRs: always squash-merged to `main`; the branch is deleted on merge.
```

### Task B6: Community docs

- [ ] **Step 1: `CONTRIBUTING.md`** (repo root)

```markdown
# Contributing

Thanks for your interest! This project records Programmers solving history via
passive ActionCable observation. Before contributing, two things to know:

1. **YAGNI is constitutional here.** The tool's owner is a job-seeker whose time
   budget is the real constraint — most feature ideas are declined. Open an issue
   first; PRs for undiscussed features are usually closed.
2. **The protocol is private and unstable.** Protocol claims require measured
   evidence — "should work" is not accepted; "verified on lesson X on date Y" is.

## Dev setup

- JDK 21 (Kotlin/Spring — arrives with Phase 1), `gh` CLI
- One-time after clone: `git config core.hooksPath .githooks`
  (Claude Code users get this automatically via a SessionStart hook)

## Flow

issue → branch `<type>/<issue#>-<slug>` → PR → **squash merge** (branch auto-deleted).
Direct pushes to `main` are blocked by branch protection. Conventional Commits,
English only — code, comments, commits, docs.

## The wiki push gate (you will meet it)

`.githooks/pre-push` blocks any branch push whose range contains no
`docs/llm-wiki/` change. This repo treats *recording decisions* as part of the
work: if your branch decided anything, add an ADR under
`docs/llm-wiki/wiki/decisions/` (see existing ones for the format). For
genuinely record-free changes (typo fixes), add an auditable trailer:
`git commit --amend --no-edit --trailer 'Wiki-Skip: <reason>'`.

## AI-assisted PRs are welcome

This project is itself AI-assisted. Requirements: say so in the PR (checkbox in
the template), include what you verified yourself and how, and make sure you can
explain every line — "the model wrote it" is not a review answer.

## Security / privacy

Session cookies, emails, and personal solving history flow through this tool.
Never commit any of those; test fixtures must be scrubbed (see
development-rules §7). Vulnerabilities: email the owner (profile) instead of
opening a public issue.
```

- [ ] **Step 2: `.github/ISSUE_TEMPLATE/bug_report.yml`**

```yaml
# NOTE: block style on purpose — flow mappings broke YAML parsing here
# (commas/`?` inside prose split flow entries; caught by coordinator review).
name: Bug report
description: Something broke
labels: [bug]
body:
  - type: textarea
    id: symptom
    attributes:
      label: Symptom
      description: What happens, including exact messages/output
    validations:
      required: true
  - type: textarea
    id: reproduce
    attributes:
      label: Reproduce
      description: Steps; for protocol issues include the lesson id and the date observed
    validations:
      required: true
  - type: textarea
    id: expected
    attributes:
      label: Expected
    validations:
      required: true
  - type: input
    id: env
    attributes:
      label: Environment
      placeholder: OS / JDK / browser
```

- [ ] **Step 3: `.github/ISSUE_TEMPLATE/feature_request.yml`**

```yaml
name: Feature request
description: Propose a change (read CONTRIBUTING first — YAGNI applies)
labels: [enhancement]
body:
  - type: textarea
    id: problem
    attributes:
      label: Problem
      description: What concrete problem does this solve for a user recording their solving history?
    validations:
      required: true
  - type: textarea
    id: proposal
    attributes:
      label: Proposal
    validations:
      required: true
  - type: textarea
    id: alternatives
    attributes:
      label: Alternatives considered
      description: Including "do nothing" — why is that not enough?
```

- [ ] **Step 4: `.github/ISSUE_TEMPLATE/config.yml`**

```yaml
blank_issues_enabled: true
```

- [ ] **Step 5: `.github/pull_request_template.md`**

```markdown
## Summary

## Changes
-

## Test
- [ ] What was run and the actual result (cite output, not intentions)

## Checklist
- [ ] AI-assisted? Disclosed above, and I can explain every line
- [ ] Wiki gate: ADR added for decisions, or `Wiki-Skip:` trailer with a reason
- [ ] English only; Conventional Commits

Closes #
```

- [ ] **Step 6: README — add before the License section**

```markdown
## Contributing

Issues and PRs are welcome — read [CONTRIBUTING.md](CONTRIBUTING.md) first
(issue-first, squash-only, English-only, and a push gate that asks you to
record decisions). AI-assisted contributions are explicitly welcome.
```

### Task B7: ADR + wiki registration + progress

- [ ] **Step 1: `docs/llm-wiki/wiki/decisions/2026-08-04-issue-first-squash-flow.md`**

```markdown
---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [record-keeping, git-flow, open-source]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-record-keeping-design.md]
---

# Issue-first flow, squash-only merges

## Context
Work landed on `main` directly (initial docs) or via ad-hoc branches. Going
open-source means outside contributors need one predictable flow, and the owner
already runs issue→branch→PR→squash everywhere else.

## Options considered
- **A. Keep ad-hoc** — no server enforcement, drift guaranteed
- **B. Flow by convention only** — docs say it, nothing enforces it
- **C. Flow + server enforcement + skills** — branch protection (PR required,
  admins included), squash-only with auto-delete, and project skills
  /issue /commit /pull-request encoding the repo's gates

## Decision
**C.** Branch names `<type>/<issue#>-<slug>`; every merge is a squash; the
owner's GitLab skills stay untouched globally and are shadowed in-repo by
GitHub-adapted project skills.

## Rationale
Server-side protection is the only mechanism that binds strangers and the owner
equally (`enforce_admins: true` — dogfooding). Skills make the cheap path the
correct path: gates (protocol evidence, TDD pairing, wiki gate) live inside
/commit and /pull-request instead of relying on memory.

## Accepted costs
- No emergency direct push — toggling protection off is the documented escape
- Same-name skill shadowing (issue/commit) relies on directory-scope resolution
- CI status checks deferred with CI itself (Phase 1) — until then, protection
  requires only the PR shape, not green checks

## Outcome
_This decision's own PR is the first end-to-end run of the flow._
```

- [ ] **Step 2**: Register in `index.md` (Decisions section, append) +
  `log.md` entry `## [2026-08-04] ingest | issue-first squash flow decision → 1 created (ADR)` +
  `.harness/state/progress.md`: add Phase 0.8 section (flow installed, skills, settings, community docs — with the real PR/commit refs).

### Task B8: Commit, PR B, merge

- [ ] **Step 1**: Commits (coordinator, logical groups): skills / enforcement docs /
  community docs / wiki+progress. Conventional messages, English, no AI trailers
  (the /commit skill's own rule, applied from now on).
- [ ] **Step 2**: `git push -u origin "chore/<m>-dev-workflow"` — wiki gate passes
  via B7's ADR. `gh pr create` (body per the pull-request skill's template,
  `Closes #<m>`; note in Test: gh api GET outputs + negative push test).
- [ ] **Step 3**: After user review: `gh pr merge --squash` (auto-delete now active),
  `git checkout main && git pull`, confirm skills resolve:
  in a fresh session `/issue` must show the project version (GitHub, not GitLab).

---

## Self-review (done at write time)

- **Spec coverage**: D1→A0–A3 · D2/D3→B1–B3,B5 · D4→B5 · D5→B4 · D6→B6 · D8→Part
  ordering · D7 recorded as deferred (no CI task — intentional). §5 verification →
  A2 (sweep, fences, links, E2E, smoke) + B4 GETs + B8 negative test.
- **Placeholders**: none — skills, ADRs, community docs, and settings calls are
  complete contents; `<n>`/`<m>` are runtime-returned numbers by design.
- **Consistency**: branch names use returned issue numbers; glossary headings in A1
  match the anchors B5 edits; keep-list in A2 matches spec §3.1; no worker runs git.
