---
type: source
project: programmers-tracker
tags: [프로토콜, 설계]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md]
---

# 2026-08-04 세션 요약

## 핵심 주장
1. 프로그래머스 채점은 REST 가 아니라 **Rails ActionCable WebSocket** 이다.
2. ActionCable 스트림은 커넥션이 아니라 **채널 파라미터로 스코프**되므로 수동 관찰이 가능하다.
3. **제출 이력 API 가 존재하지 않는다** — 채점 결과는 그 순간에 잡지 않으면 영구 소실된다.
4. `partTitle` 은 알고리즘 유형이 아니다 (689개 중 47개만) — 태그는 외부 어휘가 필요하다.
5. `challengeable_id` 와 codes 키를 혼동하면 **조용히** 실패한다.

## 이 소스가 갱신한 페이지
[[syntheses/protocol-reverse-engineering]] ·
[[concepts/actioncable-broadcast-observation]] · [[concepts/verdict-classification]] ·
[[entities/programmers-actioncable]] · [[entities/solved-ac]] · [[entities/baekjoonhub]] ·
[[decisions/2026-08-04-passive-broadcast-observation]] ·
[[decisions/2026-08-04-solve-in-web-editor]] ·
[[decisions/2026-08-04-solved-ac-tag-vocabulary]] ·
[[decisions/2026-08-04-reject-vector-db]] ·
[[decisions/2026-08-04-no-ai-debugger]]
