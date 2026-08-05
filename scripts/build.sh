#!/usr/bin/env bash
# Build gate — assembles the artifact without re-running tests.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew build -x test
