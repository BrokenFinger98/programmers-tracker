# Test environment: no Spring in layer tests, split integration task, fixture-file enforcement

Date: 2026-08-04 · Status: accepted · Issue: #6

## Context

Step 2 of the ActionCable client work introduces the first parser tests. Three
environment questions had to be settled before the test suite grows: whether layer
tests boot a Spring context, how integration tests (which need a live session
cookie) are kept out of the default gate, and whether parser tests may use
hand-written JSON.

## Decision

1. **No Spring context in layer tests.** Parser/service tests are plain JUnit 5 +
   Kotest assertions + MockK. `@SpringBootTest` stays only in the single existing
   context-load test. Spring slice tests come later, only for web controllers.
2. **Gradle task split for integration.** The default `test` task excludes
   `@Tag("integration")`; a separate `integrationTest` task includes only that tag.
   `scripts/test.sh` keeps running the default `test` task (unit + layer only).
   Integration tests read the session cookie from `TRACKER_SESSION_FILE`
   (default `~/.ps/session`) and skip via JUnit assumption when it is absent.
3. **Fixture-file enforcement.** Every normal-path and verdict-path parser test
   loads a measured `fixtures/*.jsonl` capture through the `FixtureLoader` helper
   in the test `support/fixtures` package. Inline JSON literals are allowed only
   for cases that cannot have a measured capture (malformed JSON, synthetic
   boundary values). Object-mother builders remain required for expected objects.

## Rationale

- Context boot per test class makes the suite minutes-slow for code that never
  touches Spring; parsers are plain functions over JSON.
- Integration tests hit the real Programmers server and need a personal cookie —
  they must never run in CI or block the local gate, yet stay one command away.
- Hand-written JSON verifies the protocol we imagined, not the one measured
  (dev rules §6.2); routing tests through fixture files keeps every assertion
  anchored to a real capture.

## Accepted costs

- Wiring errors (bean config, serialization config drift) surface only in the
  context-load test or later slice tests, not in layer tests.
- `integrationTest` must be run manually and can silently go stale until invoked.
- Fixture discipline adds friction when a new message shape appears: capture
  first, then test.
