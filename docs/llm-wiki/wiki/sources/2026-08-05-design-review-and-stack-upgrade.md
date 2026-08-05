---
type: source
project: programmers-tracker
tags: [stack, architecture, design-review, spring-boot, orchestration]
created: 2026-08-05
updated: 2026-08-05
sources: [raw/sessions/2026-08-05-design-review-and-stack-upgrade.md]
---

# 2026-08-05 design-review and stack-upgrade session summary

## Key claims
1. The ActionCable client works from Kotlin against the real judge — measured twice
   (Boot 3.5/JVM 21, then Boot 4.1/JVM 25), each time confirming subscription plus the full
   browser-triggered run sequence.
2. A framework BOM silently overrides versions you declared yourself, and a version catalog
   does not fix it — measured on the same dependency under two Spring Boot majors.
3. Four claims that had been repeated as protocol facts were never measured; the protocol
   document survived review because it labels its own uncertainty, the design document did not.
4. Recording a grading must not be gated on fetching the code: the verdict is unrecoverable,
   the code is re-fetchable, and the original sequence had that backwards.
5. The best queue for the write path is the raw frame log we were already required to keep —
   durable by construction, and crash recovery becomes a directory scan.
6. An idle observation socket closed silently after ~30 minutes; cause unestablished, so it
   stays out of the protocol document but proves the client's session-end signalling is absent.
7. Spring Boot 3.5 reached OSS EOL on 2026-06-30, which forced the 4.x upgrade independently
   of preference.

## Pages this source updated
[[decisions/2026-08-05-backend-stack]] · [[decisions/2026-08-05-hexagonal-architecture]] ·
[[decisions/2026-08-05-capture-pipeline-stages]] · [[decisions/2026-08-05-write-serialization]] ·
[[decisions/2026-08-05-failure-taxonomy]] · [[concepts/bom-version-shadowing]] ·
[[concepts/assumption-vs-measurement]] · [[concepts/actioncable-broadcast-observation]]
