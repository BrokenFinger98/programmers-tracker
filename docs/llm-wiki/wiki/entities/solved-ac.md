---
type: entity
project: programmers-tracker
tags: [solved.ac, 태그, 외부의존]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md]
---

# solved.ac

백준 문제의 알고리즘 태그·티어를 제공하는 커뮤니티 서비스.
**백준 온라인 저지는 2026년 5월 종료했으나 solved.ac API 는 살아 있다** (2026-08-04 실측).

- `GET /api/v3/tag/list?page=N` — 태그 어휘 180종
- `GET /api/v3/problem/lookup?problemIds=…` — 100개씩 배치 조회
- **Cloudflare 챌린지** 때문에 순수 HTTP 클라이언트로는 막힌다. 브라우저 컨텍스트 필요

우리는 **채점기가 아니라 분류 어휘**를 쓴다. 따라서 백준 종료의 영향을 받지 않는다.
다만 외부 의존이므로 `.ps/tag-vocab.json` 스냅샷으로 고정한다.

→ [[decisions/2026-08-04-solved-ac-tag-vocabulary]]
