---
type: entity
project: programmers-tracker
tags: [프로그래머스, actioncable, websocket]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md]
---

# 프로그래머스 ActionCable

프로그래머스 채점기의 실체. 사실관계는 `docs/programmers-protocol.md` 가 유일한 출처이며
여기서는 성격만 기록한다.

- 주소 `wss://ws.programmers.co.kr:443/cable`, 서브프로토콜 `actioncable-v1-json`
- 채널이 문제 유형별로 갈린다 — 알고리즘 `Challenge::AlgorithmChannel`,
  SQL `Challenge::DatabaseChannel`
- **필드 명명이 채널마다 다르다** — 알고리즘 camelCase, SQL snake_case.
  파서가 양쪽을 모두 받아야 하며, 이 비대칭이 도메인까지 올라오면 전 계층이 오염된다
- **SQL 은 `finish` 를 보내지 않는다** — `finish` 대기로 종료를 판정하면 무한 대기

액션: `run` · `submit` · `save` · `stop` · `reset` · `finish`.
`run` 은 제출 이력에 남지 않으면서 에러 전문을 주는 유일한 경로다.

→ [[concepts/actioncable-broadcast-observation]] · [[concepts/verdict-classification]]
