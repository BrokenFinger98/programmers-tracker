#!/usr/bin/env bash
# Runs a command and fails if it created one of the home-directory defaults.
#
# `tracker.record-repo` defaults to `~/ps-records` — on a user's machine, their real solving
# history, which reconciliation stages with `git add --all`. A test that boots a Spring
# context without overriding that property writes THERE, and the only symptom is a directory
# quietly appearing in a home directory. Measured 2026-08-06 (#44): a context configured with
# `SpringApplication.setDefaultProperties` was outranked by `application.yml`, so the override
# silently did nothing and the suite still passed.
#
# Existence before and after, so no test ordering can defeat it, and it catches the class
# rather than the one test that happened to be found.
#
# Usage: ./scripts/no-home-writes.sh ./scripts/test.sh
set -uo pipefail
cd "$(dirname "$0")/.."

DEFAULTS=("$HOME/ps-records")

for path in "${DEFAULTS[@]}"; do
  [ -e "$path" ] || continue
  echo "✖ $path exists before the run, so this guard cannot tell what the run created." >&2
  echo "  In CI that IS the failure. Locally, move it aside or run the command directly." >&2
  exit 1
done

"$@"
status=$?

for path in "${DEFAULTS[@]}"; do
  [ -e "$path" ] || continue
  echo "" >&2
  echo "✖ the run wrote to $path — the DEFAULT value of tracker.record-repo." >&2
  echo "  On a user's machine that is their real record repository, and reconciliation" >&2
  echo "  commits whatever it finds there. Point every tracker.* path at a @TempDir." >&2
  echo "  Note: SpringApplication.setDefaultProperties is OUTRANKED by application.yml —" >&2
  echo "  pass --tracker.record-repo=<temp> as a command-line argument instead." >&2
  exit 1
done

exit $status
