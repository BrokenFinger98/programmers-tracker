#!/usr/bin/env bash
# Test gate — runs the full test suite.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew test verifyEveryTestClassRan

# The sensor's hand-off decisions ship in extension/decide.js and are tested with the same file
# (#332). node is required in CI, where a silent skip would un-earn the check; a contributor's
# machine without node is told, not failed.
if command -v node >/dev/null 2>&1; then
  node --test extension/decide.test.js
elif [ -n "${CI:-}" ]; then
  echo "node is required to test the extension's decisions" >&2
  exit 1
else
  echo "node not found — extension decisions not tested on this machine"
fi
