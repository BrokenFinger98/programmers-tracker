#!/usr/bin/env bash
# Lint gate — fails the build on any ktlint violation.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew ktlintCheck
