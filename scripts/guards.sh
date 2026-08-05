#!/usr/bin/env bash
# Constitution guards — rules CLAUDE.md states that nothing else enforces.
# Runnable locally; CI calls the same script so the two can never drift.
set -uo pipefail
cd "$(dirname "$0")/.."

fail=0
report() { printf '\n\033[31mFAIL\033[0m  %s\n' "$1"; fail=1; }
pass() { printf '\033[32mok\033[0m    %s\n' "$1"; }

# ---------------------------------------------------------------------------
# 1. Integration tests must never run in CI.
#    They hit the real Programmers account, so the default `test` task has to
#    exclude the tag. Verified statically because the guard must hold even when
#    no integration test exists yet.
# ---------------------------------------------------------------------------
if grep -q 'excludeTags("integration")' build.gradle.kts; then
  pass "default test task excludes @Tag(\"integration\")"
else
  report "build.gradle.kts no longer excludes @Tag(\"integration\") from the default test task"
fi

if grep -rn "integrationTest" .github/workflows >/dev/null 2>&1; then
  report "a workflow invokes integrationTest — integration tests must never run in CI"
else
  pass "no workflow invokes integrationTest"
fi

# ---------------------------------------------------------------------------
# 2. No credentials or records in the repository.
#    Records belong in ps-records; session cookies belong in memory or an
#    ignored file. Both are absolute prohibitions in CLAUDE.md.
# ---------------------------------------------------------------------------
tracked_ps=$(git ls-files '.ps' | grep -v '^\.ps/\.gitkeep$' || true)
if [ -n "$tracked_ps" ]; then
  report "credential-scoped files are tracked:"$'\n'"$tracked_ps"
else
  pass "no tracked files under .ps/ except .gitkeep"
fi

records=$(git ls-files | grep -E '(^|/)(submissions\.jsonl|attempts/[0-9]{3}\.)' || true)
if [ -n "$records" ]; then
  report "solving records are committed to this repository:"$'\n'"$records"
else
  pass "no solving records committed"
fi

# A real session cookie is a long opaque hex/base64 run with no word separators.
# Synthetic placeholders (`fake-value-for-tests`) carry hyphens or underscores and
# are excluded by the character class, so tests stay writable while a genuine
# credential — or a fixture that was never scrubbed (development-rules §7.3) — is caught.
secrets=$(git grep -nIE '_session_production=[A-Za-z0-9%+/=]{24,}' -- \
  ':!scripts/guards.sh' ':!docs/**' || true)
if [ -n "$secrets" ]; then
  report "something shaped like a live session cookie is committed:"$'\n'"$secrets"
else
  pass "no session-cookie-shaped strings committed"
fi

# ---------------------------------------------------------------------------
# 3. English-only artifacts (development-rules §12).
#    Deliberately narrow: Korean STRING LITERALS are legitimate measured
#    protocol data (`실패 (시간 초과)`), and fixtures, the protocol doc and the
#    design doc all contain them on purpose. What must never be Korean is prose
#    we wrote — and the automatable, false-positive-free slice of that is
#    Kotlin comments.
# ---------------------------------------------------------------------------
korean_comments=$(git grep -nIE '^[[:space:]]*(//|\*|/\*).*[가-힣]' -- '*.kt' '*.kts' || true)
if [ -n "$korean_comments" ]; then
  report "Kotlin comments must be written in English:"$'\n'"$korean_comments"
else
  pass "no Korean prose in Kotlin comments"
fi

printf '\n'
if [ "$fail" -ne 0 ]; then
  echo "guards: FAILED"
  exit 1
fi
echo "guards: all checks passed"
