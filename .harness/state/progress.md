# Progress

날짜로 시작해 시간순 union 머지가 가능하게 한다.

## [2026-08-04] Phase 0 — 프로토콜 리버스 엔지니어링 ✅

프로그래머스 채점 프로토콜 전 구간 규명. 산출물 `docs/programmers-protocol.md` (15장).

- ActionCable WebSocket 확정 (REST 아님) — `wss://ws.programmers.co.kr:443/cable`
- 알고리즘 · SQL 양쪽 채널 엔드투엔드 검증 (풀이 수 90 → 92, 레이팅 1371 → 1372)
- verdict 5종 전부 실측 재현 (PASS / WRONG / TIMEOUT / RUNTIME_ERROR / COMPILE_ERROR)
- **브로드캐스트 수동 관찰 검증** — 별도 프로세스가 쿠키만으로 브라우저 발사 결과 수신
- 제출 이력 API 부재 확인 (번들 API 경로 전수 조사)
- solved.ac 태그 어휘 180종 확보 · 백준 210문제 태그 대조

## [2026-08-04] Phase 0.5 — 설계 ✅

- 설계 문서 `docs/superpowers/specs/2026-08-04-programmers-tracker-design.md` (13장)
- 개발 규칙 `CLAUDE.md`(헌법) + `docs/development-rules.md`(컨벤션)
- LLM Wiki 구조 + 스킬 3종
- 저장소 구조 확정 — programmers-tracker(public) + ps-records(public)

## [2026-08-04] Phase 0.7 — 기록 체계 정비 ✅

스펙 `docs/superpowers/specs/2026-08-04-record-keeping-design.md` (8958fe4).

- 결정 기록 단일 권위 = wiki ADR — `.harness/state/decisions.md` 폐지 (parity 대조: 5건 상위집합 확인)
- push 게이트 `.githooks/pre-push` — push 범위에 위키 변경 강제, `Wiki-Skip:` 트레일러 탈출구
- SessionStart 훅 `.claude/hooks/inject-state.sh` — state·index 재주입(compact 복구) + hooksPath 멱등 설치
- ADR 4건 신설 · raw 세션 1건 · 전역 리마인더 가드(레포 밖, 별도 적용)

## [2026-08-04] Phase 1 — 구현 ⏳

`docs/superpowers/specs/…-design.md` 11장 구현 순서 참조. 1번(Kotlin WebSocket 구독 재현)부터.
