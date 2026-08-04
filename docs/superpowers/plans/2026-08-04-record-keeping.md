# 기록 보존 체계 구현 계획 (record-keeping)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 결정 기록을 위키 ADR 로 단일화하고, push 게이트로 증류를 강제하며, compact 후 state 를 자동 복구한다.

**Architecture:** git native pre-push 훅(`.githooks/`)이 push 범위의 `docs/llm-wiki/` 변경을 검사해 차단/통과. 프로젝트 SessionStart 훅이 state 재주입 + hooksPath 멱등 설치. `.harness/state/decisions.md` 는 참조 갱신 후 삭제.

**Tech Stack:** bash 5.x · jq · git hooks (pre-push) · Claude Code hooks (SessionStart)

**Spec:** [`docs/superpowers/specs/2026-08-04-record-keeping-design.md`](../specs/2026-08-04-record-keeping-design.md) (커밋 8958fe4)

**계획 단계에서 이미 확정된 사실 (재검증 불필요):**
- parity check 완료 — `decisions.md` 6건 중 5건은 대응 ADR 이 **상위집합** (merge 불필요, 삭제만). 6번째(저장소 2개)만 ADR 신설 (Task 4)
- 전역 `core.hooksPath` 미설정 — hooksPath 충돌 없음
- bash 5.3 · jq `/opt/homebrew/bin/jq` · `%(trailers:key=X,valueonly)` 포맷 지원 확인
- `scripts/check.sh` 등 Quality Gate 스크립트 **아직 미존재** (Phase 1 산출물) — 이 브랜치는 셸 시나리오 검증으로 갈음
- 스펙 §4 에 없는 raw 세션 기록 + sources stub 을 Task 5 에 추가 — 위키 스키마가 ADR frontmatter 에 `sources:` 를 요구하고 고아 페이지를 금지하므로 스펙 누락을 계획에서 보완

---

### Task 0: 브랜치 생성

- [ ] **Step 1: 브랜치**

```bash
git checkout -b feat/record-keeping
```

---

### Task 1: push 게이트 E2E 테스트 하네스 (RED)

**Files:**
- Create: `<scratchpad>/e2e-wiki-gate.sh` (스크래치 — 레포에 커밋하지 않는다)

훅보다 테스트를 먼저 만든다. 훅이 없는 상태에서 시나리오 1이 실패하는 것(RED)을 확인한 뒤 Task 2 에서 훅을 작성한다(GREEN).

- [ ] **Step 1: 하네스 작성** — 아래 전문을 스크래치 디렉터리에 저장

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
git add -A && git commit -qm "seed" && git push -q origin HEAD  # 최초 push 는 훅 설치 전
if [ -n "$HOOK_SRC" ] && [ -f "$HOOK_SRC" ]; then
  mkdir -p .githooks && cp "$HOOK_SRC" .githooks/pre-push && chmod +x .githooks/pre-push
  git config core.hooksPath .githooks
fi

expect_block() { # $1=label, rest=push args
  local label="$1"; shift
  if git push "$@" >/dev/null 2>&1; then bad "$label — 통과해버림 (차단 기대)"; else ok "$label — 차단됨"; fi
}
expect_pass() {
  local label="$1"; shift
  if git push "$@" >/dev/null 2>&1; then ok "$label — 통과"; else bad "$label — 차단됨 (통과 기대)"; fi
}

# 1. 위키 변경 없는 push → 차단
echo a > a.txt && git add a.txt && git commit -qm "feat: no wiki"
expect_block "S1 위키 없음" origin HEAD

# 2. 위키 변경 추가 → 통과
echo w >> docs/llm-wiki/index.md && git add -A && git commit -qm "docs(wiki): ingest"
expect_pass "S2 위키 있음" origin HEAD

# 3. 위키 없는 새 커밋 + Wiki-Skip 트레일러 → 통과
echo b > b.txt && git add b.txt \
  && git commit -qm "fix: typo" -m "Wiki-Skip: typo fix only"
expect_pass "S3 트레일러" origin HEAD

# 4. 신규 브랜치, 위키 없음 → 차단
git checkout -qb feat/nowiki
echo c > c.txt && git add c.txt && git commit -qm "feat: no wiki on new branch"
expect_block "S4 신규 브랜치 위키 없음" origin feat/nowiki

# 5. 같은 신규 브랜치에 위키 커밋 추가 → 통과
echo w2 >> docs/llm-wiki/index.md && git add -A && git commit -qm "docs(wiki): ingest"
expect_pass "S5 신규 브랜치 위키 있음" origin feat/nowiki

# 6. 브랜치 삭제 push → 통과
expect_pass "S6 브랜치 삭제" origin --delete feat/nowiki

# 7. 태그 push → 통과 (게이트는 refs/heads/* 만 본다)
git tag v0-test
expect_pass "S7 태그" origin v0-test

# 8. --dry-run 도 게이트를 태우는지 실측 (문서화 목적 — 결과를 그대로 보고)
git checkout -q - >/dev/null 2>&1 || git checkout -q main 2>/dev/null || git checkout -q master
echo d > d.txt && git add d.txt && git commit -qm "feat: dry-run probe"
if git push --dry-run origin HEAD >/dev/null 2>&1; then
  say "  ℹ️  S8 --dry-run: 게이트 미발화 또는 통과"
else
  say "  ℹ️  S8 --dry-run: 게이트 발화·차단 확인"
fi

say ""; say "결과: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
```

- [ ] **Step 2: RED 확인 — 훅 없이 실행**

```bash
bash <scratchpad>/e2e-wiki-gate.sh ""
```

Expected: `S1 위키 없음 — 통과해버림 (차단 기대)` ❌ 와 `S4` ❌ 를 포함해 **FAIL ≥ 2, exit 1**. (게이트가 없으니 차단 시나리오가 전부 실패해야 정상)

---

### Task 2: push 게이트 구현 (GREEN)

**Files:**
- Create: `.githooks/pre-push`

- [ ] **Step 1: 훅 작성** — 아래 전문

```bash
#!/usr/bin/env bash
# wiki push gate — push 범위에 docs/llm-wiki/ 변경이 없으면 차단한다.
# 목적: 브랜치 단위 증류(/wiki-ingest) 강제. 결정·노하우가 기록 없이 publish 되는 것을 막는다.
# 원칙: fail-open — git 오류·예상 밖 입력은 전부 통과. 이 게이트는 "무의식적 누락" 방지
# 장치이지 우회 방지 장치가 아니다. 의도적 예외는 커밋 트레일러 `Wiki-Skip: <사유>` 로 남긴다.
# 근거: docs/superpowers/specs/2026-08-04-record-keeping-design.md §3.3
set -u

WIKI_DIR="docs/llm-wiki/"
Z40="0000000000000000000000000000000000000000"

while read -r local_ref local_sha remote_ref remote_sha; do
  [ -z "${local_ref:-}" ] && continue

  # 브랜치만 게이트한다 — 태그·notes 는 통과.
  # remote_ref 기준이어야 한다: local_ref 는 refspec 소스 리터럴이라
  # `git push origin HEAD` 시 "HEAD" 로 온다 (실측 — E2E S1).
  case "$remote_ref" in
    refs/heads/*) ;;
    *) continue ;;
  esac

  # 브랜치 삭제 push — 검사할 커밋이 없다
  [ "$local_sha" = "$Z40" ] && continue

  if [ "$remote_sha" = "$Z40" ]; then
    # 신규 브랜치: 어느 리모트에도 없는 커밋만 검사
    range=("$local_sha" --not --remotes)
  else
    range=("$remote_sha..$local_sha")
  fi

  # push 되는 새 커밋 수 — 0이면 새로 공개되는 것이 없다 (기존 커밋에 브랜치 포인터만)
  total=$(git rev-list --count "${range[@]}" -- 2>/dev/null) || { echo "wiki-gate: skip (rev-list 실패 — fail-open)" >&2; continue; }
  [ "$total" -eq 0 ] && continue

  # 범위 안에 위키 변경이 있으면 통과
  wiki=$(git rev-list --count "${range[@]}" -- "$WIKI_DIR" 2>/dev/null) || { echo "wiki-gate: skip (rev-list 실패 — fail-open)" >&2; continue; }
  if [ "$wiki" -gt 0 ]; then
    echo "wiki-gate: pass — $WIKI_DIR 변경 포함. PR 전 /wiki-lint 권장." >&2
    continue
  fi

  # 탈출구: 범위 내 커밋의 Wiki-Skip 트레일러 (사유를 감사 흔적으로 남긴다)
  reason=$(git log "${range[@]}" --format='%(trailers:key=Wiki-Skip,valueonly)' 2>/dev/null | grep -m1 . || true)
  if [ -n "$reason" ]; then
    echo "wiki-gate: skip by trailer — Wiki-Skip: $reason" >&2
    continue
  fi

  echo "" >&2
  echo "✖ wiki-gate: push 범위($total commits)에 $WIKI_DIR 변경이 없습니다." >&2
  echo "  이 브랜치의 결정·노하우를 먼저 기록하세요: /wiki-ingest" >&2
  echo "  기록할 것이 정말 없다면 사유를 트레일러로 남기세요:" >&2
  echo "    git commit --amend --no-edit --trailer 'Wiki-Skip: <사유>'" >&2
  exit 1
done

exit 0
```

- [ ] **Step 2: 실행 권한**

```bash
chmod +x .githooks/pre-push
```

- [ ] **Step 3: GREEN 확인 — E2E 재실행**

```bash
bash <scratchpad>/e2e-wiki-gate.sh "$(pwd)/.githooks/pre-push"
```

Expected: `S1~S7 전부 ✅, PASS=7 FAIL=0, exit 0`. S8(ℹ️) 결과는 그대로 최종 보고에 기록한다.

- [ ] **Step 4: 이 레포에 게이트 활성화** (이후 Task 12 의 실전 push 가 게이트를 실제로 타게 된다)

```bash
git config core.hooksPath .githooks && git config core.hooksPath
```

Expected: `.githooks`

- [ ] **Step 5: 커밋**

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

### Task 3: SessionStart 주입 훅

**Files:**
- Create: `.claude/hooks/inject-state.sh`
- Modify: `.claude/settings.json` (기존 permissions 블록 보존 — hooks 키만 merge)

- [ ] **Step 1: 스크립트 작성** — 아래 전문

```bash
#!/usr/bin/env bash
# SessionStart hook (project) — 세션 시작·compact 복구 시 세션 밖 기억을 재주입하고,
# wiki push 게이트(.githooks)를 멱등 설치한다.
# fail-open: 어떤 실패도 세션 시작을 막지 않는다 (항상 exit 0).
# 근거: docs/superpowers/specs/2026-08-04-record-keeping-design.md §3.4
cat >/dev/null 2>&1  # stdin 소비 (SessionStart input 미사용)

root=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0

# 1. push 게이트 멱등 설치
if [ -d "$root/.githooks" ]; then
  current=$(git -C "$root" config core.hooksPath 2>/dev/null || true)
  [ "$current" = ".githooks" ] || git -C "$root" config core.hooksPath .githooks 2>/dev/null || true
fi

# 2. state + 위키 인덱스 재주입 (없는 파일은 skip — 클로너는 goal.md 가 없다)
ctx=""
for f in ".harness/state/goal.md" ".harness/state/progress.md" "docs/llm-wiki/index.md"; do
  p="$root/$f"
  [ -f "$p" ] || continue
  ctx="${ctx}=== ${f} ===
$(cat "$p")

"
done
[ -z "$ctx" ] && exit 0

jq -n --arg c "[세션 밖 기억 재주입 — 시작/compact 복구. index 의 Decisions 절에서 기존 결정과의 충돌을 감지하라]
$ctx" '{hookSpecificOutput:{hookEventName:"SessionStart",additionalContext:$c}}' 2>/dev/null
exit 0
```

- [ ] **Step 2: 실행 권한 + 단위 검증 (레포 안 — 3파일 주입)**

```bash
chmod +x .claude/hooks/inject-state.sh
bash .claude/hooks/inject-state.sh </dev/null | jq -r '.hookSpecificOutput.additionalContext' | grep '^==='
```

Expected (순서 포함):
```
=== .harness/state/goal.md ===
=== .harness/state/progress.md ===
=== docs/llm-wiki/index.md ===
```

- [ ] **Step 3: 단위 검증 (goal.md 없는 클로너 상황 + hooksPath 자동 설치)**

스크립트는 Step 5 에서야 커밋되므로 클론에는 아직 없다 — 원본 레포의 스크립트를
절대경로로 실행한다 (스크립트는 cwd 의 git root 만 보므로 검증 의미 동일).

```bash
R=$(pwd) && W=$(mktemp -d) && git clone -q "$R" "$W/c" && cd "$W/c"
bash "$R/.claude/hooks/inject-state.sh" </dev/null | jq -r '.hookSpecificOutput.additionalContext' | grep '^===' ; git config core.hooksPath ; cd "$R" && rm -rf "$W"
```

Expected: `goal.md` 줄 **없이** progress·index 2줄만, 이어서 `.githooks` (자동 설치 확인).

- [ ] **Step 4: settings.json 훅 등록** — 기존 키(permissions 등)를 보존하고 아래 `hooks` 키를 merge

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

- [ ] **Step 5: 커밋**

```bash
git add .claude/hooks/inject-state.sh .claude/settings.json
git commit -m "feat(hooks): inject state on session start, auto-install hooks path

SessionStart (all sources incl. compact) re-injects goal/progress and
the wiki index so a compacted session recovers its decisions context,
and idempotently sets core.hooksPath=.githooks so the push gate is on
for Claude Code users without manual setup. Fail-open, always exit 0.
See docs/superpowers/specs/2026-08-04-record-keeping-design.md §3.4."
```

주의: 이 훅의 **실제 SessionStart 발화**는 다음 세션에서만 검증 가능하다. 최종 보고의 "남은 위험"에 명시할 것.

---

### Task 4: ADR 4건 신설

**Files:**
- Create: `docs/llm-wiki/wiki/decisions/2026-08-04-two-public-repos.md`
- Create: `docs/llm-wiki/wiki/decisions/2026-08-04-decisions-live-in-wiki.md`
- Create: `docs/llm-wiki/wiki/decisions/2026-08-04-wiki-push-gate.md`
- Create: `docs/llm-wiki/wiki/decisions/2026-08-04-global-project-wiki-split.md`

- [ ] **Step 1: two-public-repos** (decisions.md 6번 항목의 마이그레이션 — 유일하게 ADR 이 없던 결정)

```markdown
---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [저장소, 공개전략]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md, raw/sessions/2026-08-04-record-keeping-design.md]
---

# 저장소 2개 · 양쪽 public

## 맥락
코드·설계 문서·풀이 기록을 어디에 어떻게 둘 것인가. 풀이 기록에는 실패 이력·힌트
사용 단계까지 포함되고, 저장소는 포트폴리오를 겸한다.

## 검토한 선택지
- **A. 단일 레포** — 코드와 기록을 한 곳에
- **B. 2레포 · 양쪽 public** — `programmers-tracker`(코드+설계+위키) / `ps-records`(기록)
- **C. 2레포 · 기록만 private** — 실패 이력 비공개

## 결정
**B.**

## 이유
설계 문서를 코드와 분리하면 반드시 어긋나므로 한 레포에 둔다. 기록은 성격(개인 데이터)
과 수명(계정 단위)이 코드와 달라 분리한다. `ps-records` 를 public 으로 두는 것은
**실패 이력·힌트 사용 단계까지 포함한 성장 서사가 자산**이라는 판단이다.

## 받아들인 비용
- 실패까지 공개하는 심리 비용
- 자격증명·개인정보 관리가 한층 엄격해야 한다 — **gitignore 예외 없음**
  (세션 쿠키·이메일은 어떤 경우에도 커밋 금지, [[decisions/2026-08-04-global-project-wiki-split]] 의
  raw 원문 반입 금지 규칙과 같은 축)

## 결과
2026-08-04 두 레포 생성. 이 결정은 `.harness/state/decisions.md` 에만 있다가
[[decisions/2026-08-04-decisions-live-in-wiki]] 에 따라 ADR 로 이관됐다 —
이중 기록이 첫날부터 발산한 실례(6건 vs 5건)가 바로 이 항목이다.
```

- [ ] **Step 2: decisions-live-in-wiki**

```markdown
---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [기록체계, 위키, 단일화]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-record-keeping-design.md]
---

# 결정 기록은 위키 ADR 단일 권위

## 맥락
결정이 `.harness/state/decisions.md`(요약 append)와 `wiki/decisions/`(상세 ADR) 두 곳에
쓰이고 있었다. 운영 첫날 실측: state 6건 vs 위키 5건 — **하루 만에 발산했다**
("저장소 2개" 결정이 위키에 없었다). 어긋난 두 기록은 어느 쪽이 진실인지 알 수 없게 한다.

## 검토한 선택지
- **A. 2단 유지** — state 는 요약 인덱스, 위키는 상세 (원설계)
- **B. decisions.md 폐지** — 위키 ADR 이 유일한 권위
- **C. 위키 결정 페이지 폐지** — state 파일만

## 결정
**B.** `.harness/state/decisions.md` 를 삭제하고 `docs/llm-wiki/wiki/decisions/`
(1건 1파일)만 남긴다. 세션 시작 시의 요약 스캔은 `index.md` 의 Decisions 절
(1결정 1줄)이 대신한다 — ingest 가 어차피 유지하는 파일이라 동기화 부담이 없다.

## 이유
이중 쓰기는 반드시 발산한다(첫날 실측). 프로토콜 사실을 `docs/programmers-protocol.md`
한 곳에만 두는 기존 원칙(위키 스키마 §5.1)의 일반화다: **한 사실은 한 곳에, 나머지는 참조.**
C 는 위키의 이중 목적(개발 기억 + 포트폴리오, 스키마 §0)을 버리게 되어 기각.

state 의 역할은 재정의된다 — **state = 위치**(goal·progress: 어디까지 왔나),
**위키 = 지식**(무엇을 왜 결정했나).

## 받아들인 비용
- 세션 시작 시 결정 전문이 아니라 제목만 스캔하게 된다. 충돌이 의심되면 해당 ADR 을
  한 번 더 열어야 한다 (1단계 추가)
- ADR 작성이 append 한 줄보다 무겁다 → 게이트는 존재만 검사하고 품질은 리뷰가 맡아
  stub ADR 로 시작하는 것을 허용한다

## 결과
_구현 후 갱신 — parity 대조(5건 상위집합 확인) 후 decisions.md 삭제._
```

- [ ] **Step 3: wiki-push-gate**

```markdown
---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [기록체계, git훅, 강제장치]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-record-keeping-design.md]
---

# push 게이트로 증류를 강제한다 (native pre-push)

## 맥락
세션 원문은 auto-compact 후에도 디스크에 남는다(230MB transcript 실측 — compact 는
in-context 만 압축). 따라서 잃는 것은 원본이 아니라 **증류**다. 전역 위키 실측:
리마인더가 매 세션 떴는데도 한 달간 75개 세션(2.4GB) 적체, ingest 0회.
**넛지만으로는 증류가 일어나지 않고**, 증류는 맥락이 신선할 때가 가장 싸다.

## 검토한 선택지
강제 수위 — **A. 넛지만**(전역에서 실패 실증) · **B. commit 단위 차단**(토큰 비용,
같은 내용 반복 merge 로 페이지 오염) · **C. 브랜치/push 단위 차단**
메커니즘 — **㉮ Claude PreToolUse deny** · **㉯ git native pre-push**
탈출구 — **ⓐ permissionDecision "ask"**(흔적이 안 남음) · **ⓑ 커밋 트레일러**(감사 가능)

## 결정
**C + ㉯ + ⓑ.** `.githooks/pre-push` 가 push 범위에 `docs/llm-wiki/` 변경이 없으면
차단한다. 예외는 `Wiki-Skip: <사유>` 트레일러 — 사유가 히스토리에 감사 흔적으로 남는다.

## 이유
㉯ 가 ㉮ 를 3축에서 이긴다: **터미널 직접 push 도 잡고**, push 범위를 추정(HEAD 기준)이
아니라 **stdin 으로 정확히 받으며**, Claude Code 전용이 아니라 **도구·플랫폼 중립**이다
(GitHub/GitLab, 어떤 AI 도구든). 차단 시 stderr 는 Bash 도구 결과로 모델에게 보이므로
"차단 → /wiki-ingest → 재시도" 흐름이 성립한다.

fail-open 원칙: git 오류·예상 밖 입력은 전부 통과. 이 게이트는 **무의식적 누락 방지
장치이지 우회 방지 장치가 아니다.**

## 받아들인 비용
- 클로너 설치 마찰 — `git config core.hooksPath .githooks` 1커맨드 (Claude Code 는
  SessionStart 훅이 자동 설치)
- 트레일러 남용 가능 — 기계는 존재만 보고, 남용은 PR 리뷰·/wiki-lint 가 잡는다
- 관행 밖 push 형태(비 HEAD refspec 등)는 통과한다 — 의도된 한계

## 결과
_구현 후 갱신 — 이 게이트를 도입한 브랜치 자체가 첫 실전 통과 사례(dogfooding)._
```

- [ ] **Step 4: global-project-wiki-split**

```markdown
---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [기록체계, 위키, 계층화]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-record-keeping-design.md]
---

# 전역/프로젝트 위키는 3계층으로 분리한다

## 맥락
개인 PC 전역 위키(`~/Desktop/llm-wiki`)와 이 레포의 위키(`docs/llm-wiki/`)가 병존한다.
"위키가 두 군데로 분리되고, 프로젝트 작업이 개인 위키에 안 쌓이는 것 아닌가"라는 우려.

## 검토한 선택지
- **A. 전역 단일화** — 프로젝트 위키 폐지, 전역에 몰기
- **B. 프로젝트 단일화** — 전역 폐지
- **C. 3계층 분리** — raw=개인 PC(자동) / 1차 증류=레포 위키 / 2차 증류=전역 위키

## 결정
**C.** 같은 내용의 중복이 아니라 **계층이 다른** 배치다:
raw(모든 세션 원문) → 1차 증류(프로젝트 결정·개념) → 2차 증류(범프로젝트 일반화).

## 이유
프로젝트 위키가 레포 안이어야 하는 4근거 — ① 포트폴리오(위키 스키마 §0 이중 목적),
② 클론하면 지식+워크플로우가 같이 옴, ③ push 게이트가 성립(같은 레포여야 기계 검증),
④ 지식의 수명이 코드와 같음. A 는 이 넷을 모두 잃고, 전역 위키는 raw 원문·타 프로젝트
기록이 섞여 있어 **공개 목적지가 될 수 없다**(구조적).

"개인 PC 에 안 쌓인다"는 우려는 사실이 아님을 실측으로 확인: 전역 SessionEnd/PreCompact
아카이브 훅은 user-level 이라 cwd 무관 발화한다. 2026-08-04 이 세션의 실제 transcript 로
훅을 실행해 전역 inbox 에 551k 파일이 생기는 것을 확인했다. **raw 는 계속 전역에 쌓인다.**

원칙은 [[decisions/2026-08-04-decisions-live-in-wiki]] 와 동일: 한 사실은 한 곳에,
나머지는 참조.

## 받아들인 비용
- 전역에서 프로젝트 지식을 찾으려면 레지스트리(전역 위키에 프로젝트당 1페이지)가
  필요하다 — 전역 위키 정비는 별건으로 유예
- **프로젝트 위키에 raw 원문 반입 금지가 절대 규칙이 된다** — 세션 원문에는 쿠키·이메일이
  흐르고 이 레포는 public 이다
- 전역 리마인더 훅이 이 레포에서 오작동 넛지가 되므로 가드가 필요하다 — 훅은 설정 계층 간
  merge 되어 프로젝트에서 끌 수 없고, **전역 스크립트의 자진 후퇴가 유일한 방법**

## 결과
_구현 후 갱신._
```

---

### Task 5: raw 세션 기록 + sources stub

**Files:**
- Create: `docs/llm-wiki/raw/sessions/2026-08-04-record-keeping-design.md`
- Create: `docs/llm-wiki/wiki/sources/2026-08-04-record-keeping-design.md`

- [ ] **Step 1: raw 세션 기록** (불변 — 이후 수정 금지)

```markdown
# 2026-08-04 기록 보존 체계 설계 세션

> raw 큐레이션 기록. 세션 원문(transcript)이 아니라 사람이 다시 볼 가치가 있는
> 사실·결정·틀린 가설의 증류다. 원문은 개인 PC 전역 아카이브에 있다.

## 실측 사실

1. **auto-compact 는 디스크 transcript 를 지우지 않는다.** 230MB transcript 가
   PreCompact 5회 이상을 겪고도 온전히 append 유지 중임을 확인. 잃는 것은 원본이
   아니라 *증류* 다.
2. **넛지만으로 증류는 일어나지 않는다.** 전역 위키 inbox: 한 달간 75개 세션(2.4GB)
   적체, ingest 0회 — 리마인더는 매 세션 떴다.
3. **이중 기록은 하루 만에 발산했다.** `.harness/state/decisions.md` 6건 vs
   `wiki/decisions/` 5건 ("저장소 2개" 결정 누락).
4. **훅은 설정 계층 간 merge 된다** (user/project/local 이 전부 실행) — 프로젝트
   설정으로 전역 훅을 끌 수 없다. 전역 스크립트의 자진 후퇴가 유일한 가드 방법.
5. **전역 아카이브 훅은 cwd 무관 발화한다.** 이 세션의 실제 transcript 로
   `wiki-archive-session.sh` 를 실행 → 전역 inbox 에 551k 파일 생성 확인 (직후 삭제,
   실파일은 세션 종료 시 생성됨).
6. git `%(trailers:key=X,valueonly)` 포맷·bash 5.3·jq 가용 확인. 전역
   `core.hooksPath` 미설정 — 레포 로컬 설정과 충돌 없음.

## 내린 결정

[[decisions/2026-08-04-decisions-live-in-wiki]] ·
[[decisions/2026-08-04-wiki-push-gate]] ·
[[decisions/2026-08-04-global-project-wiki-split]] ·
[[decisions/2026-08-04-two-public-repos]] (state 에만 있던 결정의 이관)

## 틀렸던 가설 (보존 — 스키마 §5.2)

- **"전역 wiki-ingest 스킬이 이름 충돌로 잡힌 것"** — 오진. 전역에는 wiki-* 스킬이
  아예 없다. 실제 원인은 전역 *훅* 의 경로 하드코딩이었다. 스킬 개명(ptw-ingest)은
  아무것도 고치지 못했을 것이다.
- **"아카이브 훅을 cwd 기반 분기로 고치자"** — 폐기. 프로젝트 위키에는 inbox 개념이
  없고(소비자 부재), 공개 레포 워킹트리에 세션 원문(쿠키·이메일 포함)을 떨구는
  경로를 새로 뚫는 제안이었다.
- **"compact 되면 대화가 소실된다"** — 절반만 사실. in-context 는 압축되지만 디스크
  원본은 남는다. 문제 정의가 "원본 보존"에서 "증류 강제"로 바뀌었고 설계 전체가
  그에 따라 달라졌다.

## 산출물

스펙 `docs/superpowers/specs/2026-08-04-record-keeping-design.md` (커밋 8958fe4) ·
구현 계획 `docs/superpowers/plans/2026-08-04-record-keeping.md`
```

- [ ] **Step 2: sources stub**

```markdown
---
type: source
project: programmers-tracker
tags: [기록체계, 위키, git훅]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-record-keeping-design.md]
---

# 2026-08-04 기록 보존 체계 설계 세션 요약

## 핵심 주장
1. auto-compact 는 디스크 transcript 를 지우지 않는다 — 잃는 것은 원본이 아니라 **증류**다.
2. 넛지만으로 증류는 일어나지 않는다 (전역 inbox 75건/2.4GB/월, ingest 0회 실측).
3. 이중 기록은 하루 만에 발산한다 (state 6건 vs 위키 ADR 5건 실측).
4. 훅은 설정 계층 간 merge — 프로젝트가 전역 훅을 끌 수 없다.
5. 전역 아카이브 훅은 cwd 무관 발화 — 프로젝트 위키를 레포에 둬도 raw 는 개인 PC 에 쌓인다 (실증).

## 이 소스가 갱신한 페이지
[[decisions/2026-08-04-two-public-repos]] ·
[[decisions/2026-08-04-decisions-live-in-wiki]] ·
[[decisions/2026-08-04-wiki-push-gate]] ·
[[decisions/2026-08-04-global-project-wiki-split]]
```

---

### Task 6: index.md · log.md 등록

**Files:**
- Modify: `docs/llm-wiki/index.md`
- Modify: `docs/llm-wiki/log.md`

- [ ] **Step 1: index.md — Decisions 절 끝에 4줄 append** (기존 `- 2026-08-04 [[decisions/2026-08-04-no-ai-debugger]] — AI 디버거 제어 미채택` 줄 뒤)

```markdown
- 2026-08-04 [[decisions/2026-08-04-two-public-repos]] — 저장소 2개 · 양쪽 public
- 2026-08-04 [[decisions/2026-08-04-decisions-live-in-wiki]] — 결정 기록은 위키 ADR 단일 권위
- 2026-08-04 [[decisions/2026-08-04-wiki-push-gate]] — push 게이트로 증류 강제 (native pre-push)
- 2026-08-04 [[decisions/2026-08-04-global-project-wiki-split]] — 전역/프로젝트 위키 3계층 분리
```

- [ ] **Step 2: index.md — Sources 절 끝에 1줄 append**

```markdown
- 2026-08-04 [[sources/2026-08-04-record-keeping-design]]
```

- [ ] **Step 3: log.md — 끝에 append**

```markdown

## [2026-08-04] ingest | 기록 보존 체계 설계 → 6 created (ADR 4 · source 1 · raw 1), 0 updated
```

- [ ] **Step 4: 커밋**

```bash
git add docs/llm-wiki
git commit -m "docs(wiki): record record-keeping decisions as ADRs

Four ADRs (two-public-repos migrated from decisions.md, plus
decisions-live-in-wiki, wiki-push-gate, global-project-wiki-split),
one raw session record, one source stub; registered in index and log.
Includes discarded hypotheses per wiki schema §5.2."
```

---

### Task 7: CLAUDE.md 참조 전환 (5개소)

**Files:**
- Modify: `CLAUDE.md`

각 edit 은 old → new 정확 치환이다.

- [ ] **Step 1: 세션 시작 절** — old:

```
**1. state 3종을 이 순서로 읽는다.** 맥락 없이 요청부터 처리하지 않는다.

```
.harness/state/goal.md        현재 목표. "결정 대기" 면 후보 제시부터
.harness/state/progress.md    단계별 현황
.harness/state/decisions.md   의사결정 이력 — 요청이 충돌하면 그 자리에서 확인
```
```

new:

```
**1. 세션 훅이 아래 3개를 주입한다. 주입이 안 보이면 이 순서로 직접 읽는다.** 맥락 없이 요청부터 처리하지 않는다.

```
.harness/state/goal.md        현재 목표. "결정 대기" 면 후보 제시부터
.harness/state/progress.md    단계별 현황
docs/llm-wiki/index.md        Decisions 절 스캔 — 요청이 기존 결정과 충돌하면 해당 ADR 열람
```
```

- [ ] **Step 2: Architecture 절** — old: `변경 시 PR + `.harness/state/decisions.md` 갱신.` → new: `변경 시 PR + `docs/llm-wiki/wiki/decisions/` ADR 추가.`

- [ ] **Step 3: Quality Gate — 추가 게이트 목록에 1줄 추가.** old:

```
- `.harness/state/progress.md` 갱신분이 같은 브랜치에 포함
```

new:

```
- `.harness/state/progress.md` 갱신분이 같은 브랜치에 포함
- 의사결정이 있었던 브랜치는 같은 브랜치에 **wiki ADR** — push 게이트(`.githooks/pre-push`)가
  위키 변경 없는 push 를 차단한다 (예외는 커밋 트레일러 `Wiki-Skip: <사유>`)
```

- [ ] **Step 4: State 파일 운영 절** — 3개 sub-edit:

(a) old: `` `.harness/state/` 는 **세션 밖 기억**이다. 대화가 끊겨도 작업을 재개할 수 있게 한다. `` → new: `` `.harness/state/` 는 **세션 밖 기억**이다. 대화가 끊겨도 작업을 재개할 수 있게 한다.
**state = 위치**(어디까지 왔나), **위키 = 지식**(무엇을 왜 결정했나 — `docs/llm-wiki/`). ``

(b) old: `3. `state/decisions.md` — 의사결정 이력. 요청이 이전 결정과 **충돌하면 그 자리에서 확인**` → new: `3. `docs/llm-wiki/index.md` — Decisions 절 스캔. 요청이 이전 결정과 **충돌하면 해당 ADR 확인**`

(c) old: `| 설계 결정 | `decisions.md` 에 *왜* (이유·대안·근거) append |` → new: `| 설계 결정 | wiki ADR 신설 — `docs/llm-wiki/wiki/decisions/<날짜>-<slug>.md` (1건 1파일) |`

- [ ] **Step 5: 보고 형식** — old: `4. **새 `decisions.md` entry** (의사결정 발생 시)` → new: `4. **새 wiki ADR** (의사결정 발생 시 — `docs/llm-wiki/wiki/decisions/`)`

---

### Task 8: README · .gitignore 참조 전환

**Files:**
- Modify: `README.md`
- Modify: `.gitignore`

- [ ] **Step 1: README 구조 트리** — old:

```
├── .claude/commands/           프로젝트 전용 위키 커맨드
├── .harness/state/             세션 밖 기억 (goal · progress · decisions)
```

new:

```
├── .claude/commands/           프로젝트 전용 위키 커맨드
├── .githooks/                  push 게이트 — 위키 기록 없는 push 차단
├── .harness/state/             세션 밖 기억 (goal · progress)
```

- [ ] **Step 2: README — 구조 코드블록 닫는 ``` 바로 뒤에 설치 안내 추가**

```markdown

> 클론 후 1회: `git config core.hooksPath .githooks` — push 게이트 활성화.
> Claude Code 는 세션 시작 훅이 자동으로 설정한다.
```

- [ ] **Step 3: .gitignore 주석** — old: `# 개인 작업 상태 (progress·decisions 는 커밋한다)` → new: `# 개인 작업 상태 (progress 는 커밋한다)`

---

### Task 9: decisions.md 삭제

**Files:**
- Delete: `.harness/state/decisions.md`

parity 는 계획 단계에서 완료 — 5건 전부 대응 ADR 이 상위집합, 6번째는 Task 4 에서 신설됨. 재대조 불필요.

- [ ] **Step 1: 참조가 남았는지 최종 확인**

```bash
grep -rn "decisions\.md" --include="*.md" . | grep -v "docs/superpowers" | grep -v "docs/llm-wiki" || echo CLEAN
```

Expected: `CLEAN` (스펙·플랜·위키 안의 역사 서술은 남아도 된다 — 살아있는 참조만 없으면 된다)

- [ ] **Step 2: 삭제**

```bash
git rm .harness/state/decisions.md
```

---

### Task 10: progress.md 갱신 + 참조 전환 커밋

**Files:**
- Modify: `.harness/state/progress.md`

- [ ] **Step 1: Phase 0.5 절과 Phase 1 절 사이에 삽입**

```markdown
## [2026-08-04] Phase 0.7 — 기록 체계 정비 ✅

스펙 `docs/superpowers/specs/2026-08-04-record-keeping-design.md` (8958fe4).

- 결정 기록 단일 권위 = wiki ADR — `.harness/state/decisions.md` 폐지 (parity 대조: 5건 상위집합 확인)
- push 게이트 `.githooks/pre-push` — push 범위에 위키 변경 강제, `Wiki-Skip:` 트레일러 탈출구
- SessionStart 훅 `.claude/hooks/inject-state.sh` — state·index 재주입(compact 복구) + hooksPath 멱등 설치
- ADR 4건 신설 · raw 세션 1건 · 전역 리마인더 가드(레포 밖, 별도 적용)

```

- [ ] **Step 2: 커밋**

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

(직전 Task 9 의 `git rm` 이 스테이징에 포함돼 함께 커밋된다)

---

### Task 11: 전역 리마인더 가드 (레포 밖 — 브랜치에 포함 불가)

**Files:**
- Modify: `~/.claude/hooks/wiki-remind.sh` (사용자 홈 — 이 레포 PR 에 들어가지 않는다)

- [ ] **Step 1: stdin 소비 줄과 inbox 계산 사이에 가드 삽입** — old:

```bash
cat >/dev/null 2>&1  # stdin 비우기 (SessionStart input은 사용 안 함)

inbox="$HOME/Desktop/llm-wiki/raw/inbox"
```

new:

```bash
cat >/dev/null 2>&1  # stdin 비우기 (SessionStart input은 사용 안 함)

# 자체 위키(docs/llm-wiki)를 가진 레포에서는 후퇴 — 전역 inbox 리마인드가 프로젝트의
# /wiki-ingest(다른 대상)를 오도한다. 2026-08-04 programmers-tracker 설계 D3.
if root=$(git rev-parse --show-toplevel 2>/dev/null) && [ -d "$root/docs/llm-wiki" ]; then
  exit 0
fi

inbox="$HOME/Desktop/llm-wiki/raw/inbox"
```

- [ ] **Step 2: 가드 동작 확인**

```bash
bash ~/.claude/hooks/wiki-remind.sh </dev/null; echo "exit=$? (이 레포 안 — 빈 출력이어야 함)"
cd "$HOME" && bash ~/.claude/hooks/wiki-remind.sh </dev/null | jq -r '.hookSpecificOutput.additionalContext' | head -1; cd - >/dev/null
```

Expected: 레포 안 — 출력 없음 + exit=0. 홈 — `[LLM Wiki] raw/inbox에 아직...` 리마인더 정상 출력 (전역 동작 보존 확인).

---

### Task 12: 실전 검증 — push 게이트 dogfooding + PR

- [ ] **Step 1: 상태 확인**

```bash
git status --short && git log --oneline main..HEAD
```

Expected: 클린 트리, 커밋 4개 (gate · inject · wiki · retire)

- [ ] **Step 2: 실전 push — 게이트가 실제로 발화하는지 stderr 관찰**

```bash
git push -u origin feat/record-keeping
```

Expected: `wiki-gate: pass — docs/llm-wiki/ 변경 포함. PR 전 /wiki-lint 권장.` 가 stderr 에 표시된 뒤 push 성공. **이 메시지가 안 보이면 게이트가 발화하지 않은 것** — `git config core.hooksPath` 가 `.githooks` 인지 확인하고 재시도.

- [ ] **Step 3: PR 생성**

```bash
gh pr create --title "feat: record-keeping — wiki single authority, push gate, compact restore" --body "$(cat <<'EOF'
## 요약
- 결정 기록 단일 권위 = wiki ADR (`.harness/state/decisions.md` 폐지 — 첫날부터 6 vs 5 발산 실측)
- push 게이트 `.githooks/pre-push`: push 범위에 `docs/llm-wiki/` 변경 없으면 차단, `Wiki-Skip:` 트레일러 탈출구, fail-open
- SessionStart 훅: state·위키 인덱스 재주입 (compact 복구) + hooksPath 멱등 설치
- ADR 4건 신설 (two-public-repos 마이그레이션 포함) · raw 세션 1건

스펙: `docs/superpowers/specs/2026-08-04-record-keeping-design.md`

## 검증
- E2E 시나리오 7종 (bare origin 왕복): 차단/통과/트레일러/신규 브랜치/삭제/태그 — 전부 통과
- inject-state 단위 2종 (3파일 주입 · goal.md 부재 클로너)
- 이 브랜치의 push 자체가 게이트 첫 실전 통과 사례

## 남은 위험
- SessionStart 훅의 실제 발화는 다음 세션에서 확인 (스크립트 단위 검증은 완료)
- Quality Gate 스크립트(scripts/*.sh)는 아직 미존재 (Phase 1 산출물) — 셸 검증으로 갈음

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review 결과 (작성 시 수행)

- **스펙 커버리지**: D1(계층 모델 — 유지 결정이라 구현 없음, ADR 로 기록됨 T4) · D2(T4~T10) · D3(T11) · D4(T1·T2·T12) · D5(T3). 스펙 §4 산출물 전항목이 태스크에 매핑됨. §4 미열거였던 raw+stub 은 위키 스키마 요구(sources 필수·고아 금지)로 T5 에 보완, 스펙 대비 추가임을 명시
- **플레이스홀더**: 없음 — 스크립트·ADR·edit 전문 포함
- **타입/이름 일관성**: `Wiki-Skip:` 트레일러 표기, `.githooks/pre-push`, `inject-state.sh`, 파일명·경로가 태스크 간 일치함을 확인
