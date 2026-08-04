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
