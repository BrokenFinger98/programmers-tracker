---
type: source
project: programmers-tracker
tags: [protocol, design]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md]
---

# 2026-08-04 Session Summary

## Key claims
1. Programmers judging is not REST but a **Rails ActionCable WebSocket**.
2. ActionCable streams are **scoped by channel parameters**, not by connection, so passive observation is possible.
3. **No submission-history API exists** — judging results not captured in the moment are lost forever.
4. `partTitle` is not an algorithm type (only 47 of 689) — tags require an external vocabulary.
5. Confusing `challengeable_id` with the codes key fails **silently**.

## Pages this source updated
[[syntheses/protocol-reverse-engineering]] ·
[[concepts/actioncable-broadcast-observation]] · [[concepts/verdict-classification]] ·
[[entities/programmers-actioncable]] · [[entities/solved-ac]] · [[entities/baekjoonhub]] ·
[[decisions/2026-08-04-passive-broadcast-observation]] ·
[[decisions/2026-08-04-solve-in-web-editor]] ·
[[decisions/2026-08-04-solved-ac-tag-vocabulary]] ·
[[decisions/2026-08-04-reject-vector-db]] ·
[[decisions/2026-08-04-no-ai-debugger]]
