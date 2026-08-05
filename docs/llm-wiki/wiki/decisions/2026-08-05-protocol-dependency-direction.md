---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [architecture, hexagonal, protocol-isolation, dependency-direction]
created: 2026-08-05
updated: 2026-08-05
sources: [raw/sessions/2026-08-05-design-review-and-stack-upgrade.md]
---

# Identity types move to `domain`; message knowledge never leaves `protocol`

Date: 2026-08-05 · Status: accepted · Issue: #24

## Context

`development-rules` §1 states the direction as `adapter → application → domain`, with
`protocol → domain` only in `parse`. Seven `application` classes import `protocol`, so the
code and the rule disagree. It surfaced when two parallel workers read the same documents
and reached opposite conclusions — one followed the precedent merged in #16, the other the
rule as written ([[concepts/orchestrated-implementation]]).

Counting the imports separates them into two kinds that behave differently:

| Kind | What | Imports |
|---|---|---|
| **Identity** | `ChannelIdentifier`, `LessonId`, `ChallengeableId`, `ChallengeableType` | 9 |
| **Message** | `SubmitMessage`, `GradingMessageMapper`, `CableEvent`, `ActionCableFrame` | 9 |

Testing each against what the rule actually protects — "when the protocol changes, only
`protocol/parse` needs fixing", and the camelCase/snake_case asymmetry must not climb up
(§2.1):

- **Identity does not violate it.** If Programmers changes the subscription identifier's
  JSON, `asJson()` and its inverse change and nothing in `application` does. There is no
  field-name asymmetry in a lesson id.
- **Messages violate it squarely.** `GradingSessionAssembler` branches on `SubmitMessage`
  types, so a renamed message or field reaches the verdict path — the most valuable code in
  the project, and the one place silent wrong data is worst.

So the rule is right about messages and over-strict about identity. That distinction is the
decision; without it, either everything gets excused or a mechanical file move is mistaken
for having fixed the real coupling.

## Options considered

- **Amend the rule to permit `application → protocol`** — rejected. It would excuse the
  message coupling along with the harmless identity coupling, and the harmful half is the
  whole reason the rule exists.
- **Move everything and keep the rule verbatim** — rejected as stated, because it treats a
  file move as equivalent to fixing the verdict path's coupling. Identity should move, but
  moving it is not the point.
- **Leave it and note it** — rejected: an inconsistency that two independent readers already
  disagreed about will be re-litigated by every later reader.
- **Split by kind (chosen).**

## Decision

1. **Identity value types belong in `domain`.** `LessonId`, `ChallengeableId` and a
   `ChannelKey` (lesson, challengeable, kind, language) move there. `protocol` keeps
   `ChannelIdentifier` as the *wire* form, built from a `ChannelKey` and parsed back into
   one. `protocol → domain` is then the correct inward direction and no exception is needed.
2. **Protocol message knowledge never reaches `application`.** `protocol/parse` converts
   wire messages into domain-level grading events, and `GradingSessionAssembler` consumes
   those. This is the part that protects the verdict path, and it gets its own issue rather
   than riding along with a file move.
3. `development-rules` §1 states both halves explicitly, so the next reader does not have to
   infer the distinction from the import list.

## Rationale

- The rule is kept where it earns its keep and relaxed where it was costing indirection for
  no protection. Rules that are stricter than their reason invite exactly the drift that
  produced this issue.
- Ordering matters: the identity move is mechanical and safe, the message boundary touches
  verdict resolution. Landing them together would put a refactor of the project's most
  correctness-critical path inside a rename-heavy diff, where a reviewer cannot see it.

## Accepted costs

- Two PRs and a rename touching most of `application`, for no behaviour change — pure
  structure work, paid now because it gets more expensive with every feature.
- `ChannelKey` and `ChannelIdentifier` are two names for closely related things, and the
  distinction (identity vs wire form) has to be explained to every newcomer.
- Until the second issue lands, the assembler still imports `SubmitMessage`; the violation
  that matters most is the one that survives longest.

## Outcome

Recorded 2026-08-05. Decision 1 lands with this issue; decision 2 is its own issue so the
verdict path is reviewed on its own diff.

Decision 2 landed 2026-08-05 as issue #29. The shape it took is not the "grading events" this
page assumed — one frame contributes several orthogonal facts, so the crossing is a record of
extracted facts. See [[decisions/2026-08-05-grading-facts-not-events]]. No `protocol` import
remains anywhere under `application`.
