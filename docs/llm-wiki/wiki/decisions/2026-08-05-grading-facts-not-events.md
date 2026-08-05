---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [architecture, protocol-isolation, capture, refactoring]
created: 2026-08-05
updated: 2026-08-05
sources: [raw/sessions/2026-08-05-design-review-and-stack-upgrade.md]
---

# The protocol crosses into `application` as facts, not as grading events

Date: 2026-08-05 · Status: accepted · Issue: #29

## Context

[[decisions/2026-08-05-protocol-dependency-direction]] decision 2 says `protocol/parse` must
hand `application` "domain-level grading events". Four files still disagreed:
`GradingSessionAssembler` called `GradingMessageMapper` six times per message,
`GradingSession` held `List<SubmitMessage>`, `ChannelCapture` detected `SubmitMessage.Start`
on a `CableEvent`, and `RawSessionReconciler` parsed stored lines through
`ActionCableFrame`/`SubmitMessage`/`StoredChannel`. A renamed Programmers message therefore
reached verdict resolution.

The word *event* in that ADR turned out to prejudge the shape. What the assembler does with a
frame is accumulate **several orthogonal things at once**: an algorithm `start` names the
action, announces how many cases are coming *and* opens the grading; a database `finish` ends
the stream *and* carries the only testcase result there is (protocol doc §6). A one-event-per-
frame hierarchy cannot express that without either fanning one frame into several events or
re-introducing the type branch on the application side.

## Options considered

- **`sealed interface GradingEvent` with a case per frame kind** — rejected. It mirrors
  `SubmitMessage` one-to-one, so it is the same coupling wearing a domain name: a new message
  type still forces a new branch in `application`, which is exactly what the boundary exists
  to prevent.
- **Keep the six mapper calls but hide them behind a port** — rejected. The port would have
  taken a `SubmitMessage`, so the message type would still be named in `application`.
- **One record of extracted facts per frame (chosen)** — `GradingFrameFacts`: action,
  terminal kind, testcase result, announced ids, announced count, error text, and whether the
  frame starts a grading. The assembler folds facts; it never asks what the frame *was*.

## Decision

1. **`domain/GradingFrameFacts`** is the only thing assembly reads out of a broadcast.
   `GradingMessageMapper.factsOf` builds it; the six per-fact mappers stay as its internals
   and as the units the measured-fixture tests drive.
2. **`GradingSession.frames` holds facts, not wire text.** The verbatim original is already
   on disk before the session settles — stage 1 appends every frame to the raw log and the
   record points at that file — so dev rules §2.4 is satisfied by bytes that outlive the
   process. A second in-memory copy would add no durability and would give a caller a weaker
   archive to mistake for the real one. A frame nothing recognised still contributes an empty
   record and holds its position, so a partly-interpreted stream is visible as such.
3. **`application/ObservedFrame`** (wire text + facts) is what the capture consumes, so
   stage-1-before-interpretation stays literally true while `ChannelCapture` names no wire
   type. The text is opaque above `protocol`.
4. **`application/FrameReader`** is the port the reconciler replays through, implemented by
   `protocol/parse/ObservedFrames`. It also answers `channelOf`, because the ActionCable
   envelope remains the only place an algorithm submit's problem family and language survive
   (protocol doc §15.2) — that parsing stays on the protocol side rather than moving up.

## Rationale

- The blast radius of a Programmers rename is now `SubmitMessage` + `GradingMessageMapper`.
  Measured: zero `protocol` imports remain anywhere under `application`, production and tests
  alike, and the 451 tests are unchanged — the same measured captures drive the same five
  verdicts through the new crossing.
- The live path and the replay path end at the same `factsOf` call, so a reconciled grading
  keeps settling to the verdict the socket loop would have produced. Two crossings would have
  been two things to keep in step.
- Facts also make the boundary testable from the outside: `FixtureLoader.facts` turns a
  capture into what `application` sees, and an application test can no longer name a message
  type even by accident.

## Accepted costs

- Two types where the older code had one (`GradingFrameFacts` and `ObservedFrame`), and a
  reader that must be injected into the reconciler instead of called statically.
- `GradingSession.frames` is now weaker than it looks: two unrecognised frames produce equal
  records, so the list proves *how many* frames were uninterpreted, not which. The raw log is
  the answer to which, and it always was.
- `protocol/parse` imports `application` for the port, as `ProblemPageCodeFetcher` already
  does for `CodeFetcher`. Consumer-declared ports are the established shape here
  ([[decisions/2026-08-05-hexagonal-architecture]]), but it does mean "`protocol` imports
  nothing upward" is not literally true and never was.

## Outcome

Landed 2026-08-05 on `refactor/29-domain-grading-events`. Pure refactor: no behaviour change,
451 tests before and after, all four gates green.
