#!/usr/bin/env bash
# SessionStart hook (project) — re-injects out-of-session memory on session start /
# compact recovery, and idempotently installs the wiki push gate (.githooks).
# fail-open: no failure may block session start (always exit 0).
# Rationale: docs/superpowers/specs/2026-08-04-record-keeping-design.md §3.4
cat >/dev/null 2>&1  # consume stdin (SessionStart input unused)

root=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0

# 1. Idempotently install the push gate
if [ -d "$root/.githooks" ]; then
  current=$(git -C "$root" config core.hooksPath 2>/dev/null || true)
  [ "$current" = ".githooks" ] || git -C "$root" config core.hooksPath .githooks 2>/dev/null || true
fi

# 2. Re-inject state + wiki index (skip missing files — cloners have no goal.md)
ctx=""
for f in ".harness/state/goal.md" ".harness/state/progress.md" "docs/llm-wiki/index.md"; do
  p="$root/$f"
  [ -f "$p" ] || continue
  ctx="${ctx}=== ${f} ===
$(cat "$p")

"
done
[ -z "$ctx" ] && exit 0

jq -n --arg c "[out-of-session memory injection — session start/compact recovery. Scan the Decisions section of the index for conflicts with existing decisions]
$ctx" '{hookSpecificOutput:{hookEventName:"SessionStart",additionalContext:$c}}' 2>/dev/null
exit 0
