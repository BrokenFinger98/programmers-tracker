# Record-Keeping Implementation Plan (record-keeping)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unify decision records under wiki ADRs as the single authority, force distillation with a push gate, and auto-restore state after compact.

**Architecture:** A git native pre-push hook (`.githooks/`) inspects the push range for `docs/llm-wiki/` changes and blocks/passes. A project SessionStart hook re-injects state + idempotently installs hooksPath. `.harness/state/decisions.md` is deleted after its references are updated.

**Tech Stack:** bash 5.x · jq · git hooks (pre-push) · Claude Code hooks (SessionStart)

**Spec:** [`docs/superpowers/specs/2026-08-04-record-keeping-design.md`](../specs/2026-08-04-record-keeping-design.md) (commit 8958fe4)

**Facts already confirmed during planning (no re-verification needed):**
- Parity check complete — 5 of the 6 `decisions.md` entries have corresponding ADRs that are **supersets** (no merge needed, delete only). Only the 6th (two public repos) gets a new ADR (Task 4)
- Global `core.hooksPath` unset — no hooksPath conflict
- bash 5.3 · jq `/opt/homebrew/bin/jq` · `%(trailers:key=X,valueonly)` format support confirmed
- Quality Gate scripts such as `scripts/check.sh` **do not exist yet** (Phase 1 deliverables) — this branch substitutes shell scenario verification
- The raw session record + sources stub absent from spec §4 are added in Task 5 — the wiki schema requires `sources:` in ADR frontmatter and forbids orphan pages, so the plan fills this spec gap

---

### Task 0: Create branch

- [ ] **Step 1: Branch**

```bash
git checkout -b feat/record-keeping
```

---

### Task 1: Push gate E2E test harness (RED)

**Files:**
- Create: `<scratchpad>/e2e-wiki-gate.sh` (scratch — do not commit to the repo)

Build the test before the hook. Confirm scenario 1 fails with no hook present (RED), then write the hook in Task 2 (GREEN).

- [ ] **Step 1: Write the harness** — save the full text below to the scratch directory

```bash
#!/usr/bin/env bash
# e2e-wiki-gate.sh — wiki push gate end-to-end scenarios against a local bare origin.
# Usage: e2e-wiki-gate.sh /abs/path/to/repo/.githooks/pre-push
# Harness is strict (set -e for setup); assertions handle expected failures explicitly.
set -u
HOOK_SRC="${1:-}"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/wiki-gate-e2e.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
PASS=0; FAIL=0

say()  { printf '%s\n' "$*"; }
ok()   { PASS=$((PASS+1)); say "  ✅ $1"; }
bad()  { FAIL=$((FAIL+1)); say "  ❌ $1"; }

# --- fixture: bare origin + clone with the hook installed ---
git init -q --bare "$WORK/origin.git"
git clone -q "$WORK/origin.git" "$WORK/clone"
cd "$WORK/clone"
git config user.email t@t && git config user.name t
mkdir -p docs/llm-wiki
echo seed > seed.txt && echo idx > docs/llm-wiki/index.md
git add -A && git commit -qm "seed" && git push -q origin HEAD  # first push happens before the hook is installed
if [ -n "$HOOK_SRC" ] && [ -f "$HOOK_SRC" ]; then
  mkdir -p .githooks && cp "$HOOK_SRC" .githooks/pre-push && chmod +x .githooks/pre-push
  git config core.hooksPath .githooks
fi

expect_block() { # $1=label, rest=push args
  local label="$1"; shift
  if git push "$@" >/dev/null 2>&1; then bad "$label — slipped through (expected block)"; else ok "$label — blocked"; fi
}
expect_pass() {
  local label="$1"; shift
  if git push "$@" >/dev/null 2>&1; then ok "$label — passed"; else bad "$label — blocked (expected pass)"; fi
}

# 1. push with no wiki change → block
echo a > a.txt && git add a.txt && git commit -qm "feat: no wiki"
expect_block "S1 no wiki" origin HEAD

# 2. add a wiki change → pass
echo w >> docs/llm-wiki/index.md && git add -A && git commit -qm "docs(wiki): ingest"
expect_pass "S2 wiki present" origin HEAD

# 3. new commit without wiki + Wiki-Skip trailer → pass
echo b > b.txt && git add b.txt \
  && git commit -qm "fix: typo" -m "Wiki-Skip: typo fix only"
expect_pass "S3 trailer" origin HEAD

# 4. new branch, no wiki → block
git checkout -qb feat/nowiki
echo c > c.txt && git add c.txt && git commit -qm "feat: no wiki on new branch"
expect_block "S4 new branch no wiki" origin feat/nowiki

# 5. add a wiki commit on the same new branch → pass
echo w2 >> docs/llm-wiki/index.md && git add -A && git commit -qm "docs(wiki): ingest"
expect_pass "S5 new branch wiki present" origin feat/nowiki

# 6. branch deletion push → pass
expect_pass "S6 branch delete" origin --delete feat/nowiki

# 7. tag push → pass (the gate only looks at refs/heads/*)
git tag v0-test
expect_pass "S7 tag" origin v0-test

# 8. measure whether --dry-run also goes through the gate (for documentation — report the result as-is)
git checkout -q - >/dev/null 2>&1 || git checkout -q main 2>/dev/null || git checkout -q master
echo d > d.txt && git add d.txt && git commit -qm "feat: dry-run probe"
if git push --dry-run origin HEAD >/dev/null 2>&1; then
  say "  ℹ️  S8 --dry-run: gate did not fire, or passed"
else
  say "  ℹ️  S8 --dry-run: gate fired and blocked"
fi

say ""; say "Result: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
```

- [ ] **Step 2: Confirm RED — run without the hook**

```bash
bash <scratchpad>/e2e-wiki-gate.sh ""
```

Expected: **FAIL ≥ 2, exit 1**, including `S1 no wiki — slipped through (expected block)` ❌ and `S4` ❌. (With no gate in place, every blocking scenario must fail — that is the correct outcome)

---

### Task 2: Push gate implementation (GREEN)

**Files:**
- Create: `.githooks/pre-push`

- [ ] **Step 1: Write the hook** — full text below

```bash
#!/usr/bin/env bash
# wiki push gate — block any push whose range contains no docs/llm-wiki/ change.
# Purpose: force per-branch distillation (/wiki-ingest). Prevents decisions and know-how
# from being published unrecorded.
# Principle: fail-open — any git error or unexpected input passes. This gate guards against
# "unconscious omission", not against circumvention. Intentional exceptions are recorded
# via the commit trailer `Wiki-Skip: <reason>`.
# Basis: docs/superpowers/specs/2026-08-04-record-keeping-design.md §3.3
set -u

WIKI_DIR="docs/llm-wiki/"
Z40="0000000000000000000000000000000000000000"

while read -r local_ref local_sha remote_ref remote_sha; do
  [ -z "${local_ref:-}" ] && continue

  # Gate branches only — tags and notes pass.
  # Must key on remote_ref: local_ref is the refspec source literal, so it
  # arrives as "HEAD" on `git push origin HEAD` (measured — E2E S1).
  case "$remote_ref" in
    refs/heads/*) ;;
    *) continue ;;
  esac

  # branch deletion push — no commits to inspect
  [ "$local_sha" = "$Z40" ] && continue

  if [ "$remote_sha" = "$Z40" ]; then
    # new branch: inspect only commits not on any remote
    range=("$local_sha" --not --remotes)
  else
    range=("$remote_sha..$local_sha")
  fi

  # number of new commits being pushed — 0 means nothing new is published (only a branch pointer to existing commits)
  total=$(git rev-list --count "${range[@]}" -- 2>/dev/null) || { echo "wiki-gate: skip (rev-list failed — fail-open)" >&2; continue; }
  [ "$total" -eq 0 ] && continue

  # pass if the range contains a wiki change
  wiki=$(git rev-list --count "${range[@]}" -- "$WIKI_DIR" 2>/dev/null) || { echo "wiki-gate: skip (rev-list failed — fail-open)" >&2; continue; }
  if [ "$wiki" -gt 0 ]; then
    echo "wiki-gate: pass — $WIKI_DIR change included. /wiki-lint recommended before PR." >&2
    continue
  fi

  # escape hatch: Wiki-Skip trailer on a commit in the range (leaves the reason as an audit trail)
  reason=$(git log "${range[@]}" --format='%(trailers:key=Wiki-Skip,valueonly)' 2>/dev/null | grep -m1 . || true)
  if [ -n "$reason" ]; then
    echo "wiki-gate: skip by trailer — Wiki-Skip: $reason" >&2
    continue
  fi

  echo "" >&2
  echo "✖ wiki-gate: no $WIKI_DIR change in the push range ($total commits)." >&2
  echo "  Record this branch's decisions and know-how first: /wiki-ingest" >&2
  echo "  If there is truly nothing to record, leave the reason as a trailer:" >&2
  echo "    git commit --amend --no-edit --trailer 'Wiki-Skip: <reason>'" >&2
  exit 1
done

exit 0
```

- [ ] **Step 2: Make executable**

```bash
chmod +x .githooks/pre-push
```

- [ ] **Step 3: Confirm GREEN — rerun the E2E**

```bash
bash <scratchpad>/e2e-wiki-gate.sh "$(pwd)/.githooks/pre-push"
```

Expected: `S1–S7 all ✅, PASS=7 FAIL=0, exit 0`. Record the S8 (ℹ️) result as-is in the final report.

- [ ] **Step 4: Enable the gate in this repo** (the live push in Task 12 will then actually go through the gate)

```bash
git config core.hooksPath .githooks && git config core.hooksPath
```

Expected: `.githooks`

- [ ] **Step 5: Commit**

```bash
git add .githooks/pre-push
git commit -m "feat(hooks): add wiki push gate (native pre-push)

Block any branch push whose range contains no docs/llm-wiki/ change,
so distillation happens while context is fresh. Escape hatch: commit
trailer 'Wiki-Skip: <reason>' (auditable). Fail-open on git errors.

Native pre-push over Claude PreToolUse: catches terminal pushes, gets
the exact pushed range on stdin, and stays tool-neutral (GitHub/GitLab,
any AI tool). Measured basis: global wiki inbox accumulated 75 sessions
/2.4GB in one month with zero ingests — nudges alone do not work.
See docs/superpowers/specs/2026-08-04-record-keeping-design.md §3.3."
```

---

### Task 3: SessionStart injection hook

**Files:**
- Create: `.claude/hooks/inject-state.sh`
- Modify: `.claude/settings.json` (preserve the existing permissions block — merge only the hooks key)

- [ ] **Step 1: Write the script** — full text below

```bash
#!/usr/bin/env bash
# SessionStart hook (project) — re-injects out-of-session memory on session start /
# compact recovery, and idempotently installs the wiki push gate (.githooks).
# fail-open: no failure blocks session start (always exit 0).
# Basis: docs/superpowers/specs/2026-08-04-record-keeping-design.md §3.4
cat >/dev/null 2>&1  # consume stdin (SessionStart input unused)

root=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0

# 1. idempotently install the push gate
if [ -d "$root/.githooks" ]; then
  current=$(git -C "$root" config core.hooksPath 2>/dev/null || true)
  [ "$current" = ".githooks" ] || git -C "$root" config core.hooksPath .githooks 2>/dev/null || true
fi

# 2. re-inject state + wiki index (skip missing files — cloners have no goal.md)
ctx=""
for f in ".harness/state/goal.md" ".harness/state/progress.md" "docs/llm-wiki/index.md"; do
  p="$root/$f"
  [ -f "$p" ] || continue
  ctx="${ctx}=== ${f} ===
$(cat "$p")

"
done
[ -z "$ctx" ] && exit 0

jq -n --arg c "[Out-of-session memory re-injection — start/compact recovery. Detect conflicts with existing decisions in the index's Decisions section]
$ctx" '{hookSpecificOutput:{hookEventName:"SessionStart",additionalContext:$c}}' 2>/dev/null
exit 0
```

- [ ] **Step 2: Make executable + unit check (inside the repo — 3-file injection)**

```bash
chmod +x .claude/hooks/inject-state.sh
bash .claude/hooks/inject-state.sh </dev/null | jq -r '.hookSpecificOutput.additionalContext' | grep '^==='
```

Expected (order included):
```
=== .harness/state/goal.md ===
=== .harness/state/progress.md ===
=== docs/llm-wiki/index.md ===
```

- [ ] **Step 3: Unit check (cloner scenario without goal.md + automatic hooksPath install)**

The script is only committed in Step 5, so the clone does not have it yet — run the original
repo's script by absolute path (the script only looks at the cwd's git root, so the
verification is equivalent).

```bash
R=$(pwd) && W=$(mktemp -d) && git clone -q "$R" "$W/c" && cd "$W/c"
bash "$R/.claude/hooks/inject-state.sh" </dev/null | jq -r '.hookSpecificOutput.additionalContext' | grep '^===' ; git config core.hooksPath ; cd "$R" && rm -rf "$W"
```

Expected: **no** `goal.md` line, only the 2 progress·index lines, followed by `.githooks` (auto-install confirmed).

- [ ] **Step 4: Register the hook in settings.json** — preserve existing keys (permissions etc.) and merge the `hooks` key below

```json
{
  "hooks": {
    "SessionStart": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/inject-state.sh"
          }
        ]
      }
    ]
  }
}
```

- [ ] **Step 5: Commit**

```bash
git add .claude/hooks/inject-state.sh .claude/settings.json
git commit -m "feat(hooks): inject state on session start, auto-install hooks path

SessionStart (all sources incl. compact) re-injects goal/progress and
the wiki index so a compacted session recovers its decisions context,
and idempotently sets core.hooksPath=.githooks so the push gate is on
for Claude Code users without manual setup. Fail-open, always exit 0.
See docs/superpowers/specs/2026-08-04-record-keeping-design.md §3.4."
```

Note: the **actual SessionStart firing** of this hook can only be verified in the next session. State this under "remaining risks" in the final report.

---

### Task 4: Create four ADRs

**Files:**
- Create: `docs/llm-wiki/wiki/decisions/2026-08-04-two-public-repos.md`
- Create: `docs/llm-wiki/wiki/decisions/2026-08-04-decisions-live-in-wiki.md`
- Create: `docs/llm-wiki/wiki/decisions/2026-08-04-wiki-push-gate.md`
- Create: `docs/llm-wiki/wiki/decisions/2026-08-04-global-project-wiki-split.md`

- [ ] **Step 1: two-public-repos** (migration of the 6th decisions.md entry — the only decision that had no ADR)

```markdown
---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [repository, publishing-strategy]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md, raw/sessions/2026-08-04-record-keeping-design.md]
---

# Two repositories · both public

## Context
Where and how to keep the code, design documents, and solve records. Solve records include
failure history and hint-usage stages, and the repositories double as a portfolio.

## Options considered
- **A. Single repo** — code and records in one place
- **B. Two repos · both public** — `programmers-tracker` (code+design+wiki) / `ps-records` (records)
- **C. Two repos · records private** — failure history stays private

## Decision
**B.**

## Rationale
Design documents drift out of sync whenever they are separated from the code, so they live in
one repo with it. Records differ from code in nature (personal data) and lifespan (per-account),
so they are split off. Keeping `ps-records` public reflects the judgment that **the growth
narrative — including failure history and hint-usage stages — is an asset**.

## Accepted costs
- The psychological cost of publishing failures too
- Credential and PII management must be stricter by a notch — **no gitignore exceptions**
  (session cookies and emails are never committed under any circumstances; the same axis as the
  raw-transcript import ban in [[decisions/2026-08-04-global-project-wiki-split]])

## Outcome
Both repos created 2026-08-04. This decision lived only in `.harness/state/decisions.md` until it
was migrated to an ADR per [[decisions/2026-08-04-decisions-live-in-wiki]] —
this very entry is the live example of dual records diverging from day one (6 vs 5).
```

- [ ] **Step 2: decisions-live-in-wiki**

```markdown
---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [record-keeping, wiki, single-authority]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-record-keeping-design.md]
---

# Decision records: wiki ADRs are the single authority

## Context
Decisions were being written in two places: `.harness/state/decisions.md` (summary append) and
`wiki/decisions/` (detailed ADRs). Measured on day one of operation: 6 state entries vs 5 wiki
entries — **they diverged within a single day** (the "two repos" decision was missing from the
wiki). Two records out of sync make it impossible to know which one is the truth.

## Options considered
- **A. Keep both tiers** — state as summary index, wiki as detail (original design)
- **B. Retire decisions.md** — wiki ADRs are the sole authority
- **C. Retire the wiki decision pages** — state file only

## Decision
**B.** Delete `.harness/state/decisions.md` and keep only `docs/llm-wiki/wiki/decisions/`
(one file per decision). The session-start summary scan is replaced by the Decisions section
of `index.md` (one line per decision) — a file ingest maintains anyway, so there is no sync burden.

## Rationale
Dual writes always diverge (measured on day one). This generalizes the existing principle of
keeping protocol facts in `docs/programmers-protocol.md` alone (wiki schema §5.1):
**one fact in one place, everything else references it.**
C is rejected because it abandons the wiki's dual purpose (dev memory + portfolio, schema §0).

State's role is redefined — **state = position** (goal·progress: how far we have come),
**wiki = knowledge** (what was decided and why).

## Accepted costs
- At session start only titles are scanned, not full decision texts. If a conflict is suspected,
  the ADR must be opened once more (one extra step)
- Writing an ADR is heavier than a one-line append → the gate checks existence only and review
  owns quality, so starting with a stub ADR is allowed

## Outcome
_Update after implementation — delete decisions.md after the parity check (5 supersets confirmed)._
```

- [ ] **Step 3: wiki-push-gate**

```markdown
---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [record-keeping, git-hooks, enforcement]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-record-keeping-design.md]
---

# Force distillation with a push gate (native pre-push)

## Context
Session transcripts survive on disk after auto-compact (230MB transcript measured — compact only
compresses in-context). So what is lost is not the original but the **distillation**. Global wiki
measurement: even with the reminder appearing every session, 75 sessions (2.4GB) piled up in one
month with zero ingests. **Nudges alone do not produce distillation**, and distillation is
cheapest while context is fresh.

## Options considered
Enforcement level — **A. nudge only** (empirically failed globally) · **B. block per commit**
(token cost; page pollution from repeatedly merging the same content) · **C. block per branch/push**
Mechanism — **㉮ Claude PreToolUse deny** · **㉯ git native pre-push**
Escape hatch — **ⓐ permissionDecision "ask"** (leaves no trace) · **ⓑ commit trailer** (auditable)

## Decision
**C + ㉯ + ⓑ.** `.githooks/pre-push` blocks when the push range contains no `docs/llm-wiki/`
change. The exception is the `Wiki-Skip: <reason>` trailer — the reason stays in history as an audit trail.

## Rationale
㉯ beats ㉮ on three axes: it **also catches direct terminal pushes**, receives the push range
**exactly on stdin** rather than estimating from HEAD, and is **tool- and platform-neutral**
rather than Claude Code-specific (GitHub/GitLab, any AI tool). On block, stderr is visible to
the model as the Bash tool result, so the "block → /wiki-ingest → retry" flow works.

Fail-open principle: any git error or unexpected input passes. This gate is a **guard against
unconscious omission, not against circumvention.**

## Accepted costs
- Cloner install friction — one command, `git config core.hooksPath .githooks` (Claude Code
  auto-installs it via the SessionStart hook)
- Trailer abuse is possible — the machine checks existence only; abuse is caught by PR review and /wiki-lint
- Unconventional push forms (non-HEAD refspecs etc.) pass — an intended limitation

## Outcome
_Update after implementation — the branch introducing this gate is itself the first live pass (dogfooding)._
```

- [ ] **Step 4: global-project-wiki-split**

```markdown
---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [record-keeping, wiki, layering]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-record-keeping-design.md]
---

# Global/project wikis split into three layers

## Context
The personal-PC global wiki (`~/Desktop/llm-wiki`) and this repo's wiki (`docs/llm-wiki/`)
coexist. Concern: "doesn't this split the wiki into two places, with project work no longer
accumulating in the personal wiki?"

## Options considered
- **A. Consolidate globally** — retire the project wiki, pile everything into the global one
- **B. Consolidate in the project** — retire the global one
- **C. Three-layer split** — raw = personal PC (automatic) / 1st distillation = repo wiki / 2nd distillation = global wiki

## Decision
**C.** Not a duplication of the same content but a placement at **different layers**:
raw (all session transcripts) → 1st distillation (project decisions·concepts) → 2nd distillation (cross-project generalization).

## Rationale
Four reasons the project wiki must live inside the repo — ① portfolio (wiki schema §0 dual
purpose), ② cloning brings knowledge + workflow together, ③ the push gate becomes possible
(machine verification requires the same repo), ④ knowledge has the same lifespan as the code.
A loses all four, and the global wiki mixes raw transcripts with other projects' records, so
it **cannot be a publication target** (structurally).

The concern "it won't accumulate on my personal PC" was measured to be false: the global
SessionEnd/PreCompact archive hooks are user-level and fire regardless of cwd. On 2026-08-04
we ran the hook against this session's actual transcript and confirmed a 551k file appearing
in the global inbox. **Raw keeps accumulating globally.**

The principle is the same as [[decisions/2026-08-04-decisions-live-in-wiki]]: one fact in one
place, everything else references it.

## Accepted costs
- Finding project knowledge from the global side requires a registry (one page per project in
  the global wiki) — global wiki housekeeping deferred as separate work
- **Importing raw transcripts into the project wiki becomes an absolute prohibition** — session
  transcripts carry cookies and emails, and this repo is public
- The global reminder hook becomes a misfiring nudge in this repo, so a guard is needed — hooks
  merge across settings layers and cannot be disabled per-project; **voluntary retreat by the
  global script is the only way**

## Outcome
_Update after implementation._
```

---

### Task 5: Raw session record + sources stub

**Files:**
- Create: `docs/llm-wiki/raw/sessions/2026-08-04-record-keeping-design.md`
- Create: `docs/llm-wiki/wiki/sources/2026-08-04-record-keeping-design.md`

- [ ] **Step 1: raw session record** (immutable — never edited afterward)

```markdown
# 2026-08-04 Record-Keeping Design Session

> A raw curation record. Not the session transcript, but a distillation of the facts,
> decisions, and wrong hypotheses worth revisiting. The transcript lives in the
> personal-PC global archive.

## Measured facts

1. **auto-compact does not delete the on-disk transcript.** Confirmed a 230MB transcript
   surviving 5+ PreCompact events fully intact and still appending. What is lost is not
   the original but the *distillation*.
2. **Nudges alone do not produce distillation.** Global wiki inbox: 75 sessions (2.4GB)
   piled up over one month, zero ingests — the reminder appeared every session.
3. **Dual records diverged within a day.** `.harness/state/decisions.md` 6 entries vs
   `wiki/decisions/` 5 (the "two repos" decision missing).
4. **Hooks merge across settings layers** (user/project/local all run) — project settings
   cannot disable a global hook. Voluntary retreat by the global script is the only guard.
5. **The global archive hook fires regardless of cwd.** Ran `wiki-archive-session.sh`
   against this session's actual transcript → confirmed a 551k file created in the global
   inbox (deleted right after; the real file is created at session end).
6. git `%(trailers:key=X,valueonly)` format, bash 5.3, and jq availability confirmed. Global
   `core.hooksPath` unset — no conflict with the repo-local setting.

## Decisions made

[[decisions/2026-08-04-decisions-live-in-wiki]] ·
[[decisions/2026-08-04-wiki-push-gate]] ·
[[decisions/2026-08-04-global-project-wiki-split]] ·
[[decisions/2026-08-04-two-public-repos]] (migration of a decision that lived only in state)

## Wrong hypotheses (preserved — schema §5.2)

- **"The global wiki-ingest skill was shadowed by a name collision"** — misdiagnosis. There
  are no wiki-* skills globally at all. The real cause was a hardcoded path in the global
  *hook*. Renaming the skill (ptw-ingest) would have fixed nothing.
- **"Fix the archive hook with cwd-based branching"** — discarded. The project wiki has no
  inbox concept (no consumer), and the proposal would have opened a new path dropping session
  transcripts (cookies and emails included) into a public repo's working tree.
- **"Compact loses the conversation"** — only half true. In-context gets compressed but the
  on-disk original remains. The problem definition shifted from "preserve the original" to
  "force distillation", and the whole design changed accordingly.

## Deliverables

Spec `docs/superpowers/specs/2026-08-04-record-keeping-design.md` (commit 8958fe4) ·
implementation plan `docs/superpowers/plans/2026-08-04-record-keeping.md`
```

- [ ] **Step 2: sources stub**

```markdown
---
type: source
project: programmers-tracker
tags: [record-keeping, wiki, git-hooks]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-record-keeping-design.md]
---

# 2026-08-04 Record-Keeping Design Session Summary

## Key claims
1. auto-compact does not delete the on-disk transcript — what is lost is not the original but the **distillation**.
2. Nudges alone do not produce distillation (global inbox 75 sessions/2.4GB/month, zero ingests, measured).
3. Dual records diverge within a day (state 6 entries vs wiki ADR 5, measured).
4. Hooks merge across settings layers — a project cannot disable global hooks.
5. The global archive hook fires regardless of cwd — even with the project wiki in the repo, raw accumulates on the personal PC (verified).

## Pages this source updated
[[decisions/2026-08-04-two-public-repos]] ·
[[decisions/2026-08-04-decisions-live-in-wiki]] ·
[[decisions/2026-08-04-wiki-push-gate]] ·
[[decisions/2026-08-04-global-project-wiki-split]]
```

---

### Task 6: Register in index.md · log.md

**Files:**
- Modify: `docs/llm-wiki/index.md`
- Modify: `docs/llm-wiki/log.md`

- [ ] **Step 1: index.md — append 4 lines at the end of the Decisions section** (after the existing `- 2026-08-04 [[decisions/2026-08-04-no-ai-debugger]] — AI debugger control not adopted` line)

```markdown
- 2026-08-04 [[decisions/2026-08-04-two-public-repos]] — two repositories · both public
- 2026-08-04 [[decisions/2026-08-04-decisions-live-in-wiki]] — decision records: wiki ADRs are the single authority
- 2026-08-04 [[decisions/2026-08-04-wiki-push-gate]] — force distillation via push gate (native pre-push)
- 2026-08-04 [[decisions/2026-08-04-global-project-wiki-split]] — global/project wiki three-layer split
```

- [ ] **Step 2: index.md — append 1 line at the end of the Sources section**

```markdown
- 2026-08-04 [[sources/2026-08-04-record-keeping-design]]
```

- [ ] **Step 3: log.md — append at the end**

```markdown

## [2026-08-04] ingest | record-keeping design → 6 created (ADR 4 · source 1 · raw 1), 0 updated
```

- [ ] **Step 4: Commit**

```bash
git add docs/llm-wiki
git commit -m "docs(wiki): record record-keeping decisions as ADRs

Four ADRs (two-public-repos migrated from decisions.md, plus
decisions-live-in-wiki, wiki-push-gate, global-project-wiki-split),
one raw session record, one source stub; registered in index and log.
Includes discarded hypotheses per wiki schema §5.2."
```

---

### Task 7: CLAUDE.md reference switch (5 sites)

**Files:**
- Modify: `CLAUDE.md`

Each edit is an exact old → new substitution.

- [ ] **Step 1: Session-start section** — old:

```
**1. Read the 3 state files in this order.** Do not process the request without context.

```
.harness/state/goal.md        Current goal. If it says "awaiting decision", present candidates first
.harness/state/progress.md    Step-by-step status
.harness/state/decisions.md   Decision history — if the request conflicts, check on the spot
```
```

new:

```
**1. The session hook injects the 3 files below. If the injection is missing, read them directly in this order.** Do not process the request without context.

```
.harness/state/goal.md        Current goal. If it says "awaiting decision", present candidates first
.harness/state/progress.md    Step-by-step status
docs/llm-wiki/index.md        Scan the Decisions section — if the request conflicts with an existing decision, open that ADR
```
```

- [ ] **Step 2: Architecture section** — old: `Changes require a PR + updating `.harness/state/decisions.md`.` → new: `Changes require a PR + an ADR in `docs/llm-wiki/wiki/decisions/`.`

- [ ] **Step 3: Quality Gate — add 1 line to the additional-gates list.** old:

```
- The updated `.harness/state/progress.md` is included in the same branch
```

new:

```
- The updated `.harness/state/progress.md` is included in the same branch
- A branch that made decisions carries a **wiki ADR** in the same branch — the push gate
  (`.githooks/pre-push`) blocks pushes without wiki changes (escape hatch: commit trailer `Wiki-Skip: <reason>`)
```

- [ ] **Step 4: State File Operations section** — 3 sub-edits:

(a) old: `` `.harness/state/` is **out-of-session memory**. It lets work resume even when the conversation is cut off. `` → new: `` `.harness/state/` is **out-of-session memory**. It lets work resume even when the conversation is cut off.
**state = position** (how far we have come), **wiki = knowledge** (what was decided and why — `docs/llm-wiki/`). ``

(b) old: `3. `state/decisions.md` — decision history. If the request **conflicts with a prior decision, check on the spot**` → new: `3. `docs/llm-wiki/index.md` — scan the Decisions section. If the request **conflicts with a prior decision, check that ADR**`

(c) old: `| Design decision | Append the *why* (reason·alternatives·basis) to `decisions.md` |` → new: `| Design decision | New wiki ADR — `docs/llm-wiki/wiki/decisions/<date>-<slug>.md` (one file per decision) |`

- [ ] **Step 5: Report Format** — old: `4. **New `decisions.md` entry** (when a decision occurred)` → new: `4. **New wiki ADR** (when a decision occurred — `docs/llm-wiki/wiki/decisions/`)`

---

### Task 8: README · .gitignore reference switch

**Files:**
- Modify: `README.md`
- Modify: `.gitignore`

- [ ] **Step 1: README structure tree** — old:

```
├── .claude/commands/           project-scoped wiki commands
├── .harness/state/             out-of-session memory (goal · progress · decisions)
```

new:

```
├── .claude/commands/           project-scoped wiki commands
├── .githooks/                  push gate — blocks pushes without wiki records
├── .harness/state/             out-of-session memory (goal · progress)
```

- [ ] **Step 2: README — add the install note right after the closing ``` of the structure code block**

```markdown

> Once after cloning: `git config core.hooksPath .githooks` — enables the push gate.
> Claude Code sets it automatically via the session-start hook.
```

- [ ] **Step 3: .gitignore comment** — old: `# personal work state (progress and decisions are committed)` → new: `# personal work state (progress is committed)`

---

### Task 9: Delete decisions.md

**Files:**
- Delete: `.harness/state/decisions.md`

Parity was completed at planning time — all 5 entries have superset ADRs, and the 6th is created in Task 4. No re-check needed.

- [ ] **Step 1: Final check for leftover references**

```bash
grep -rn "decisions\.md" --include="*.md" . | grep -v "docs/superpowers" | grep -v "docs/llm-wiki" || echo CLEAN
```

Expected: `CLEAN` (historical narration inside specs, plans, and the wiki may remain — only live references must be gone)

- [ ] **Step 2: Delete**

```bash
git rm .harness/state/decisions.md
```

---

### Task 10: Update progress.md + commit the reference switch

**Files:**
- Modify: `.harness/state/progress.md`

- [ ] **Step 1: Insert between the Phase 0.5 section and the Phase 1 section**

```markdown
## [2026-08-04] Phase 0.7 — record-keeping overhaul ✅

Spec `docs/superpowers/specs/2026-08-04-record-keeping-design.md` (8958fe4).

- Single authority for decision records = wiki ADRs — `.harness/state/decisions.md` retired (parity check: 5 supersets confirmed)
- Push gate `.githooks/pre-push` — forces wiki changes in the push range, `Wiki-Skip:` trailer escape hatch
- SessionStart hook `.claude/hooks/inject-state.sh` — re-injects state·index (compact recovery) + idempotent hooksPath install
- 4 new ADRs · 1 raw session · global reminder guard (outside the repo, applied separately)

```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md README.md .gitignore .harness/state/progress.md
git commit -m "docs: retire decisions.md — wiki ADRs are the single authority

State file and wiki ADRs diverged on day one (6 vs 5 entries). Parity
check confirmed the five existing ADRs are supersets of their state
entries; the sixth (two-public-repos) was migrated as a new ADR. Session
start now scans docs/llm-wiki/index.md Decisions section instead.
State keeps position (goal/progress); wiki keeps knowledge.
See docs/superpowers/specs/2026-08-04-record-keeping-design.md §3.2."
```

(The `git rm` from Task 9 just before is already staged and gets committed together)

---

### Task 11: Global reminder guard (outside the repo — cannot be part of the branch)

**Files:**
- Modify: `~/.claude/hooks/wiki-remind.sh` (user home — does not go into this repo's PR)

- [ ] **Step 1: Insert the guard between the stdin-consuming line and the inbox computation** — old:

```bash
cat >/dev/null 2>&1  # stdin 비우기 (SessionStart input은 사용 안 함)

inbox="$HOME/Desktop/llm-wiki/raw/inbox"
```

new:

```bash
cat >/dev/null 2>&1  # stdin 비우기 (SessionStart input은 사용 안 함)

# Retreat in repos that have their own wiki (docs/llm-wiki) — the global inbox reminder
# misleads the project's /wiki-ingest (a different target). 2026-08-04 programmers-tracker design D3.
if root=$(git rev-parse --show-toplevel 2>/dev/null) && [ -d "$root/docs/llm-wiki" ]; then
  exit 0
fi

inbox="$HOME/Desktop/llm-wiki/raw/inbox"
```

- [ ] **Step 2: Confirm the guard behavior**

```bash
bash ~/.claude/hooks/wiki-remind.sh </dev/null; echo "exit=$? (inside this repo — must print nothing)"
cd "$HOME" && bash ~/.claude/hooks/wiki-remind.sh </dev/null | jq -r '.hookSpecificOutput.additionalContext' | head -1; cd - >/dev/null
```

Expected: inside the repo — no output + exit=0. From home — the `[LLM Wiki] raw/inbox에 아직...` reminder prints normally (confirms global behavior is preserved).

---

### Task 12: Live verification — push gate dogfooding + PR

- [ ] **Step 1: Status check**

```bash
git status --short && git log --oneline main..HEAD
```

Expected: clean tree, 4 commits (gate · inject · wiki · retire)

- [ ] **Step 2: Live push — watch stderr to confirm the gate actually fires**

```bash
git push -u origin feat/record-keeping
```

Expected: `wiki-gate: pass — docs/llm-wiki/ change included. /wiki-lint recommended before PR.` appears on stderr, then the push succeeds. **If this message is not visible, the gate did not fire** — check that `git config core.hooksPath` is `.githooks` and retry.

- [ ] **Step 3: Create the PR**

```bash
gh pr create --title "feat: record-keeping — wiki single authority, push gate, compact restore" --body "$(cat <<'EOF'
## Summary
- Single authority for decision records = wiki ADRs (`.harness/state/decisions.md` retired — 6 vs 5 divergence measured from day one)
- Push gate `.githooks/pre-push`: blocks when the push range has no `docs/llm-wiki/` change, `Wiki-Skip:` trailer escape hatch, fail-open
- SessionStart hook: re-injects state · wiki index (compact recovery) + idempotent hooksPath install
- 4 new ADRs (including the two-public-repos migration) · 1 raw session record

Spec: `docs/superpowers/specs/2026-08-04-record-keeping-design.md`

## Verification
- 7 E2E scenarios (round-trips against a bare origin): block/pass/trailer/new branch/delete/tag — all passed
- 2 inject-state unit checks (3-file injection · cloner without goal.md)
- This branch's own push is the gate's first live pass

## Remaining risks
- The hook's actual SessionStart firing is confirmed in the next session (script-level unit checks done)
- Quality Gate scripts (scripts/*.sh) do not exist yet (Phase 1 deliverables) — shell verification substitutes

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review Results (performed at authoring time)

- **Spec coverage**: D1 (layer model — a keep-as-is decision, so no implementation; recorded as an ADR, T4) · D2 (T4–T10) · D3 (T11) · D4 (T1·T2·T12) · D5 (T3). Every spec §4 deliverable maps to a task. The raw+stub items unlisted in §4 are added in T5 per wiki-schema requirements (sources mandatory · no orphans), explicitly noted as an addition over the spec
- **Placeholders**: none — full texts of scripts, ADRs, and edits included
- **Type/name consistency**: `Wiki-Skip:` trailer spelling, `.githooks/pre-push`, `inject-state.sh` — filenames and paths confirmed consistent across tasks
