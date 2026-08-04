# Record-Keeping System Design — decision unification · wiki layering · push gate

> 2026-08-04 · Finalized via brainstorming. Subject to user review before implementation.
> Related: [`CLAUDE.md`](../../../CLAUDE.md) (constitution) · [`docs/llm-wiki/CLAUDE.md`](../../llm-wiki/CLAUDE.md) (wiki schema)

---

## 1. Problems (all measured)

1. **Distillation is not enforced.** Session originals survive on disk even after auto-compact
   (measured: a 230MB transcript stayed intact through 5+ PreCompact events). But nothing forces
   the original → wiki distillation. Global wiki measurement: **75 sessions (2.4GB) piled up in
   a month, 0 ingests.** "I'll do it later from the originals" is a strategy with proven failure.
   Distillation is cheapest while the context is fresh.
2. **Decision records already exist in two copies, and they diverged on day one.**
   `.harness/state/decisions.md` has 6 entries vs `docs/llm-wiki/wiki/decisions/` has 5 ADRs —
   the "two repos · both public" decision is missing from the wiki.
3. **Global and project wikis collide with no role separation.** The global SessionStart reminder
   injects the global inbox count even inside this repo, wrongly steering toward this repo's
   `/wiki-ingest` (a different target).
4. **No state re-injection after compact.** Once the early part of a session is compressed,
   work continues having lost the goal, progress, and decision context.

## 2. Decision summary

| # | Decision | Gist |
|---|---|---|
| D1 | Wiki stays in the repo + 3-layer model | raw = personal machine (automatic) · 1st distillation = repo wiki · 2nd distillation = global wiki (separate effort) |
| D2 | Decision records = wiki as single authority | `.harness/state/decisions.md` **retired**. `wiki/decisions/` ADRs are the single source |
| D3 | Global/project role separation | Only the global reminder steps back voluntarily (2-line guard). Global archive hooks stay |
| D4 | Push gate = **git native pre-push** | Block when the push range has no wiki change. Escape via the `Wiki-Skip:` trailer |
| D5 | Compact recovery hook | A SessionStart hook re-injects goal · progress · wiki index |

## 3. Details

### 3.1 D1 — Wiki layer model

| Layer | Content | Location | How it fills |
|---|---|---|---|
| Raw | All session originals | Personal machine (`~/.claude/projects` + global inbox) | Global hook, automatic (proven) |
| 1st distillation | Project decisions · concepts · full accounts | This repo's `docs/llm-wiki/` | `/wiki-ingest` + enforced by the D4 gate |
| 2nd distillation | Cross-project generalization | Global wiki `~/Desktop/llm-wiki` | **Separate effort** — during global wiki upkeep |

The principle generalizes wiki schema §5.1: **one fact lives in one place; everything else references it.**
Project knowledge lives in the repo — portfolio (schema §0 dual purpose) · shared with cloners ·
gate-enforceable · lifetime matches the code. The global wiki cannot be published (raw content and
other projects mixed in), so it cannot be the sharing destination. Adding a project registry page
to the global wiki is deferred to global upkeep (separate effort).

### 3.2 D2 — Decision record unification

- **Delete** `.harness/state/decisions.md`. Before deletion, cross-check (parity check) that the
  content of all 5 entries is fully contained in the corresponding ADRs, and merge any shortfall
  into the ADRs.
- One new migration ADR: `wiki/decisions/2026-08-04-two-public-repos.md`
- New ADRs for this design itself: `decisions-live-in-wiki` · `wiki-push-gate` ·
  `global-project-wiki-split` (one file each, ADR format — with options considered and costs)
- **Session-start reading order changed**: `goal.md` → `progress.md` → the Decisions section of
  `docs/llm-wiki/index.md` (one decision per line — a conflict-detection scan. On suspected
  conflict, open that ADR)
- Redefined meaning of state: **state = position** (how far we have come: goal · progress),
  **wiki = knowledge** (what was decided, why, and know-how)
- Files updated: `CLAUDE.md` (session-start section · Architecture section · State operations
  section · report format · Quality Gate), `README.md` line 83, `.gitignore` comments.
  `development-rules.md` unchanged
- Gate existence/quality separation: **the gate (D4) checks existence only**; ADR quality is
  the job of `/wiki-lint` and PR review

### 3.3 D4 — Push gate (git native pre-push)

**Mechanism choice.** Chose native over a Claude `PreToolUse` deny approach:

| Criterion | native pre-push | PreToolUse |
|---|---|---|
| Direct terminal push | ✅ Catches it | ❌ Cannot catch it |
| Determining the push range | ✅ Receives exact ref ranges via stdin | ⚠️ Estimates from HEAD |
| Tool-neutral | ✅ CC · Codex · manual, all of them | ❌ Claude Code only |
| GitHub/GitLab | ✅ git-level — platform-agnostic | ✅ Same |

On block, stderr is shown to the model verbatim as the Bash tool result, so the flow where the
model reads it, runs `/wiki-ingest`, and retries holds up.

**Algorithm** (`.githooks/pre-push`, committed):

```
for each (local_ref local_sha remote_ref remote_sha) in stdin:
  local_sha == 0000...     → skip           # branch-deletion push
  remote_sha == 0000...    → range = local_sha --not --remotes  # new branch
  else                     → range = remote_sha..local_sha
  range contains a docs/llm-wiki/ change      → pass (recommend /wiki-lint via stderr)
  a commit in range has a "Wiki-Skip: <reason>" trailer → pass (reason printed to stderr)
  else → exit 1 + "no wiki change in the pushed range. /wiki-ingest first;
         for exceptions use the commit trailer Wiki-Skip: <reason>"
```

**Fail-open principle.** The gate prevents *unconscious omission*; it is not a security device.
Any git command failure or unexpected input passes. Intentional bypass (trailer abuse) is caught
by PR review.

**Installation.** `git config core.hooksPath .githooks` (repo-local config).
- This environment: confirmed no global `core.hooksPath` — no conflict
- Automation: D5's SessionStart hook runs it idempotently in the same script
- Manual fallback: a 1-command note in the README (non-Claude-Code users · other tools)

### 3.4 D5 — Compact recovery hook

Project hook (`.claude/settings.json` + `.claude/hooks/inject-state.sh`, committed):

- `SessionStart` (no matcher = all sources: startup · resume · clear · compact · fork):
  injects `goal.md` + `progress.md` + `docs/llm-wiki/index.md` as `additionalContext`.
  Missing files are silently skipped (cloners have no goal.md — normal)
- The same script idempotently sets `core.hooksPath` (3.3)
- `CLAUDE.md` session-start section wording adjusted: "the hook injects them; if the injection
  is missing, read them directly" (fallback for users of other tools)

### 3.5 D3 — Global-side change (outside the repo · cannot be included in this PR)

Add a guard to `~/.claude/hooks/wiki-remind.sh`: **if the cwd repo has `docs/llm-wiki/`,
silently exit 0.** Hooks are merged across config layers, so a project cannot disable a global
hook — voluntary retreat by the global script is the only way.
`wiki-archive-session.sh` · `wiki-archive-precompact.sh` are **not modified**
(they own the raw layer — this project's sessions keep accumulating in the global inbox too.
Proven 2026-08-04).

## 4. Deliverables

| Kind | Files |
|---|---|
| New | `.githooks/pre-push` · `.claude/hooks/inject-state.sh` · 4 ADRs (`two-public-repos` migration + 3 design ADRs) |
| Modified | `.claude/settings.json` (hook registration) · `CLAUDE.md` in 5 places · `README.md` · `.gitignore` comments · `docs/llm-wiki/index.md` (ADR registration) · `docs/llm-wiki/log.md` · `.harness/state/progress.md` |
| Deleted | `.harness/state/decisions.md` (after the parity check) |
| Outside the repo | `~/.claude/hooks/wiki-remind.sh` guard (applied manually, separately) |

## 5. Verification plan

Shell hooks are not subject to the TDD pairing rule (which targets `.kt`), so the scenario
verification below substitutes for it.

1. **Unit simulation** — feed ref lines to stdin and exhaustively check the branches:
   wiki change present (pass) · absent (block) · `Wiki-Skip` trailer (pass + reason printed) ·
   branch deletion (skip) · new branch (fallback range) · git command failure (fail-open pass)
2. **E2E** — real `git push` round-trips from a clone with a bare repo in the scratchpad as
   origin: block → add an ingest commit → pass. Whether `--dry-run` fires pre-push is also
   measured here
3. **Once in the real environment** — this design's own implementation branch becomes the first
   real-world pass case (the 4 ADRs are wiki changes, so the gate passes naturally — dogfooding)
4. **inject-state** — feed JSON and check the additionalContext output + confirm skip when
   goal.md is absent

## 6. Non-goals

- **A PR-time lint-enforcing hook** — cannot catch MRs created via the web UI (GitLab practice),
  so it becomes a platform-dependent workaround. Replaced by the stderr recommendation on push pass
- **Cleaning up the 75-item (2.4GB) global inbox · a global ingest skill · a project registry** —
  global wiki upkeep is a separate effort. Out of scope for this repo
- **CI gate · a wiki-merge skill** — YAGNI (same judgment as wiki schema §4)

## 7. Accepted costs · remaining risks

- **Cloner friction**: needs the 1-command hooksPath setup or CC hook approval. Mitigated by the
  README. Without installation it runs without the gate (degraded, not broken — consistent with
  the fail-open philosophy)
- **`Wiki-Skip` can be abused**: the mechanical gate checks existence only; abuse is caught by
  PR review and `/wiki-lint`
- **Gate bypass paths exist**: unconventional forms such as non-HEAD refspec pushes pass.
  An intended limitation (an omission-prevention device, not a bypass-prevention device)
- **Dual existence of global raw and the project wiki**: intended duplication — different layers
  (raw vs distilled). Not content duplication, so unlike the decisions.md problem
- **Trust prompt for `.claude/settings.json` hooks**: right after clone, CC asks for consent to
  run hooks. As a public repo we keep hook scripts short and readable to preserve reviewability
