---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [ci, guards, coverage, tooling]
created: 2026-08-05
updated: 2026-08-05
sources: [raw/sessions/2026-08-05-design-review-and-stack-upgrade.md]
---

# CI guards are deliberately narrow, and coverage is report-only

Date: 2026-08-05 · Status: accepted · Issue: #14

## Context

CLAUDE.md states rules that no compiler and no test can enforce: integration tests must
never run in CI, no record or credential may be committed, and every committed artifact
must be English. CI is the only place these can be checked mechanically.

Two of them are text checks, and text checks in this repository have an unusual hazard:
**Korean is legitimate data here.** Measured protocol strings (`실패 (시간 초과)`,
`내부적인 오류가 발생했습니다`) appear on purpose in fixtures, in `programmers-protocol.md`,
in the design document, and will appear in the verdict classifier as string literals.
A naive "no Hangul in committed files" check fires on all of them.

The same shape of problem applies to secrets: test code legitimately contains
`_session_production=fake-value-for-tests`, which a naive cookie pattern flags.

Coverage has a different tension. The project's real risk is unexercised failure branches
(the constitution requires failure-path tests for all five verdicts), but the pure
calculators those thresholds would protect — `domain/calc` — do not exist yet.

## Options considered

- **Broad text guards (any Hangul, any `_session_production=`)** — rejected: they fail on
  legitimate measured data. A guard that cries wolf gets disabled, and then the rule it
  protected is unenforced *and* nobody notices.
- **No text guards at all** — rejected: these are stated constitution rules, and an
  unenforced rule decays silently.
- **Narrow, false-positive-free guards (chosen)** — accept false negatives, refuse false
  positives.
- **Global coverage threshold now** — rejected: over an empty `domain/` it is dead config,
  and globally it rewards writing tests that execute code without asserting anything.

## Decision

1. **English-only is checked on Kotlin comments only.** Comments are prose we wrote;
   string literals are data we received. This is the slice that is automatable with zero
   false positives.
2. **The secret check matches opaque runs of 24+ characters** (`[A-Za-z0-9%+/=]{24,}`).
   Real session cookies are opaque; synthetic placeholders carry hyphens or underscores
   and pass. This also catches a fixture that was never scrubbed
   (development-rules §7.3).
3. **Integration exclusion is verified statically** — the default `test` task must exclude
   the tag, and no workflow may invoke `integrationTest`. Static, because the guard has to
   hold while zero integration tests exist.
4. **Guards live in `scripts/guards.sh`, not in the workflow.** CI calls the same script a
   developer runs, so the two cannot drift.
5. **Kover is report-only.** A threshold is added when `domain/calc` exists, scoped to it.

## Rationale

- A guard's job is to be trusted. One false positive on legitimate measured data would
  make the whole set suspect, and the realistic outcome is that someone broadens the
  ignore list until the check means nothing.
- Narrow-but-true beats broad-but-noisy when the alternative to a guard is a human
  remembering — the guard still removes the whole class of *accidental* violation, which
  is what the constitution's prohibitions are actually defending against.
- Keeping guards in a script makes them runnable before pushing, so the feedback arrives
  where it is cheap rather than after a CI round-trip.

## Accepted costs

- **False negatives are real.** Korean prose in a Markdown document, in a commit message,
  or inside a Kotlin string used as user-facing output all pass. A deliberate leak in an
  unusual shape passes too. These guards do not replace review.
- Coverage carries no enforcement today, so nothing stops coverage from decaying until the
  threshold arrives with `domain/calc`.
- The three-OS matrix triples CI minutes for a project whose code is currently
  platform-independent; it is paying forward for the Docker and cookie-extraction work,
  where platform differences are the whole point.

## Outcome

Recorded 2026-08-05 with #14. **The deferred coverage threshold landed in #32**, once
`domain/calc` existed: branch coverage of the pure calculators must hold 95% and is at 100%.
It is read from the Kover XML rather than expressed as a Kover rule, because Kover's
verification rules cannot be narrowed to a single package and a project-wide number would
measure exactly the thing this ADR refused to chase.

#32 also added a repeatability job — the suite three times, no build cache — for the reason
this ADR did not anticipate: the guards were sound but the *tests* were not always
deterministic, and two failures passed locally every time before failing in CI. Negative-tested at authoring time: a planted realistic cookie
literal and a planted Korean comment each fail `scripts/guards.sh`, while the clean tree
passes. Related: [[decisions/2026-08-04-test-environment]] ·
[[concepts/assumption-vs-measurement]].
