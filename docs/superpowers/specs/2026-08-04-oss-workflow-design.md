# Open-Source Workflow — English Migration, Dev Flow, Community Docs

> 2026-08-04 · Approved in brainstorming. First spec written under the English-only policy it defines.
> Related: [`CLAUDE.md`](../../../CLAUDE.md) · [`docs/development-rules.md`](../../development-rules.md) · prior spec [`2026-08-04-record-keeping-design.md`](2026-08-04-record-keeping-design.md)

---

## 1. Context

The repo is public and must now assume **external contributors filing issues and PRs**
(reference model: openclaw — issue-first routing, AI-assisted-PR policy, contribution docs).
Four gaps:

1. **Everything is in Korean** — docs, hook comments, and even the push-gate's stderr,
   which an external contributor will see verbatim.
2. **No enforced dev flow** — work so far was committed straight to `main` (specs/plans)
   or through ad-hoc branches. The owner's standard flow (issue → branch → PR →
   squash + delete branch) exists only as habit.
3. **The owner's `issue`/`merge-request` skills are GitLab-specific** (glab, POLESTAR
   versioning, develop/release branch model) — unusable here, and they must stay
   untouched globally because company work still needs them.
4. **No community docs** — no CONTRIBUTING, issue forms, or PR template.

## 2. Decisions

| # | Decision | Gist |
|---|---|---|
| D1 | **English-only work artifacts** | Supersedes development-rules §12 ("write in Korean"). Everything translated except immutable history (`raw/sessions/`, existing `log.md` entries). Conversation language stays per-user. |
| D2 | **Issue-first flow, squash-only** | Every change: issue → branch `<type>/<issue#>-<slug>` → PR → squash merge + branch delete. No direct commits to `main`. |
| D3 | **Project skills `issue` / `commit` / `pull-request`** | Adapted from the global GitLab skills for `gh`; project scope shadows same-name globals inside this repo (precedent: `wiki-*`). |
| D4 | **CLAUDE.md enforces D1+D2** | New workflow section + Forbidden entries. |
| D5 | **Repo settings via `gh api`** | Squash-only, auto-delete branches, branch protection on `main` (PR required, admins included). Status checks deferred with CI. |
| D6 | **Community docs** | CONTRIBUTING.md (English), 2 issue forms (bug/feature), PR template with AI-assisted disclosure. No CoC file, SECURITY.md, CODEOWNERS, dependabot (below scale). |
| D7 | **CI deferred to Phase 1** | User decision. No workflow file now; branch protection gains required status checks when CI lands. |
| D8 | **Two issues, two PRs — translation first** | PR 1 translates; PR 2 adds workflow content in English on top. Reversed order would edit the same files twice. |

## 3. Details

### 3.1 D1 — English migration (first issue, branch `docs/<n>-english-migration`)

> Issue and PR numbers share one sequence on GitHub — merged PR #1 already took `1`,
> so `<n>` is whatever number `gh issue create` actually returns (likely 2).

Translate: `CLAUDE.md`, `docs/development-rules.md` (§12 reversed to English-only),
`README.md`, `docs/programmers-protocol.md`, both specs, the plan,
`docs/llm-wiki/` (schema CLAUDE.md, index, README, 9 ADRs, concepts, entities,
syntheses, sources stubs), `.githooks/pre-push` (comments **and stderr messages**),
`.claude/hooks/inject-state.sh`, `.claude/skills/wiki-*` (3 files),
`.harness/state/{goal,progress}.md`, `template/ps-records/`.

Keep as-is: `docs/llm-wiki/raw/sessions/*` and existing `log.md` entries — the wiki
schema declares raw immutable. New entries are English from now on. Mixed-language
history is accepted and noted in the language ADR.

Translation rules for workers: translate prose only — **no semantic edits, no
restructuring**. Code blocks, fixture data, tables of measured values, message-format
examples, and link targets stay byte-identical except for translated comments/labels.
The protocol doc is measured fact; a translation that alters a field name or number
is a defect.

New ADR: `2026-08-04-english-only-artifacts.md` — records the §12 reversal, rationale
(open-source posture), the raw/log immutability exception, and the cost (Korean
readers lose first-language docs; the original audience definition changes).

### 3.2 D2/D3 — Flow and skills (second issue, branch `chore/<n>-dev-workflow`)

Branch naming: `<type>/<issue#>-<slug>`, types `feat|fix|docs|refactor|test|chore`
(`chore` added to development-rules §11; `#` dropped from the GitLab pattern to avoid
shell quoting).

**`issue` skill** (from global `issue`): glab→`gh issue create`; drop base-branch
selection (always `main`), version step (no versioning), Hotfix type. Keep interactive
type/title/description flow, preview-confirm, per-type description templates. Apply
labels: `bug`, `enhancement`, `documentation` (GitHub defaults) + `refactor`, `test`,
`chore` (created once during implementation). Creates branch `<type>/<n>-<slug>` from
fresh `main` and checks out.

**`commit` skill** (from global `commit`): keep auto-analysis, conventional commits,
review-then-confirm, and the **no-AI-attribution rule** (no Co-Authored-By). Add
project gates: protocol-related changes need measured evidence in the body
(development-rules §11); a new production `.kt` needs its test pair in the same PR.

**`pull-request` skill** (from global `merge-request`): glab→`gh pr create`; target
fixed to `main`; keep mermaid-rich auto-generated body; body ends with `Closes #<n>`.
Integrations: push triggers the wiki gate — on block, direct the user to
`/wiki-ingest` (or a `Wiki-Skip:` trailer with a reason); after checks pass, offer
`gh pr merge --squash --delete-branch`. Skill files are written in English; runtime
conversation follows the user's language settings.

### 3.3 D4 — CLAUDE.md enforcement

New section (replacing the informal commit/branch note): every change goes
issue → branch → PR → squash; use `/issue`, `/commit`, `/pull-request`; all work
artifacts in English. Forbidden additions:

- ❌ Direct commits or pushes to `main` (server-enforced by branch protection)
- ❌ Korean (or any non-English) in committed artifacts — docs, comments, commit
  messages, wiki pages, user-facing hook output

### 3.4 D5 — Repository settings (one-time, during Issue #2)

```
gh api -X PATCH repos/BrokenFinger98/programmers-tracker \
  -f allow_squash_merge=true -f allow_merge_commit=false \
  -f allow_rebase_merge=false -f delete_branch_on_merge=true
gh api -X PUT repos/BrokenFinger98/programmers-tracker/branches/main/protection \
  (required_pull_request_reviews: approvals 0, enforce_admins: true,
   required_status_checks: null — added in Phase 1 with CI, restrictions: null)
```

`enforce_admins: true` means the owner is also blocked from direct pushes —
deliberate dogfooding; can be toggled in an emergency. Verified with a GET after.

### 3.5 D6 — Community docs (Issue #2)

- `CONTRIBUTING.md` (English): dev setup (JDK 21 planned, `git config core.hooksPath
  .githooks`), issue-first routing ("features usually start as an issue; YAGNI is
  constitutional here — most feature ideas are declined"), branch/commit/PR
  conventions, the wiki gate explained for outsiders (what blocks their push and why,
  `Wiki-Skip` escape), AI-assisted PRs welcome + disclosure requirement (openclaw
  pattern), security note (session cookies/PII — report privately, not via issues).
- `.github/ISSUE_TEMPLATE/bug_report.yml`, `feature_request.yml`, `config.yml`
  (blank issues allowed; forms mirror the `issue` skill's section templates).
- `.github/pull_request_template.md`: Summary / Changes / Test / `Closes #` /
  checkboxes: AI-assisted disclosure · wiki gate passed or `Wiki-Skip` justified.
- `README.md`: add a Contributing section linking CONTRIBUTING (English text lands
  via PR 1's translation; the section itself is PR 2).

## 4. Execution order

1. User resets local `main` to `origin/main` (done outside — blocked hook).
2. First issue filed with `gh` manually (skills don't exist yet — the flow is
   followed by hand, which is itself the dogfood) → translation PR (parallel workers
   per file cluster; disjoint files, coordinator commits).
3. Second issue → workflow PR (skills + CLAUDE.md + community docs + repo settings).
4. Both PRs squash-merged with branch deletion — first live runs of the new flow.

## 5. Verification

- **PR 1**: `git diff` inspection per cluster — fixtures/code/numbers byte-stable
  (translation-only); gate E2E harness re-run locally (its assertions don't parse
  message text, so it must stay green); `rg -n "[가-힣]"` over the repo returns only
  the declared keep-list (raw/sessions, old log entries).
- **PR 2**: skills load (`/issue` resolves to project version in-repo); `gh api` GET
  confirms merge-method + protection state; templates render on GitHub; the PR itself
  exercises issue-first + squash + auto-delete end to end.

## 6. Not doing (and why)

- CI workflow now — deferred to Phase 1 by user decision (D7)
- CODE_OF_CONDUCT.md / SECURITY.md / CODEOWNERS / dependabot / labeler — below scale;
  security contact lives as a CONTRIBUTING section
- Deleting or editing the global GitLab skills — company work depends on them;
  shadowing by project scope is the mechanism (verified precedent: `wiki-*`)
- Translating `raw/sessions/` or rewriting old `log.md` entries — immutability wins

## 7. Accepted costs · residual risks

- **Mixed-language wiki history** — raw sources stay Korean under English pages;
  accepted and recorded in the ADR
- **Translation drift risk on the protocol doc** — mitigated by translate-only rule
  and byte-stability checks on code/fixture blocks
- **Same-name skill shadowing** (`issue`, `commit`) relies on directory-scope
  resolution — verified in this repo with `wiki-*`; if resolution ever regresses,
  rename project skills (`gh-issue` etc.) as fallback
- **`enforce_admins` friction** — the owner cannot hotfix `main` directly; toggling
  protection off is the documented emergency path
- **Global-skill divergence** — the GitLab originals evolve independently; project
  skills are a fork, not a mirror (accepted)
