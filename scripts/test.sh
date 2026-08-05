#!/usr/bin/env bash
# Test gate — runs the full test suite.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew test
