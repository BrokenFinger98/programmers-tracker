---
type: source
project: programmers-tracker
tags: [프로토콜, 리버스엔지니어링, 설계, actioncable]
created: 2026-08-04
updated: 2026-08-04
---

# 2026-08-04 세션 — 프로토콜 리버스 엔지니어링 및 설계 확정

## 발단

코딩테스트 준비를 위해 "로컬에서 풀고 프로그래머스로 채점받고 모든 제출을 기록"하는
시스템을 만들고 싶다는 요구에서 출발. 기존 저장소(BrokenFinger98/Problem-Solving)를
조사한 결과 BaekjoonHub 가 `getSolvedResult().includes('정답')` 로 **정답일 때만** 동작해
실패 기록이 구조적으로 남지 않음을 소스 레벨에서 확인.

## 프로토콜 규명 경로

1. 공개된 리버스 엔지니어링 사례가 GitHub 전체에 없음을 확인 (직접 개척 필요)
2. `application.js` 번들에서 `channel.perform("submit", {codes})` 발견
   → **REST 가 아니라 ActionCable WebSocket**
3. `<meta name="action-cable-url">` → `wss://ws.programmers.co.kr:443/cable`
4. WebSocket 핸드셰이크 실측 → `101 Switching Protocols` + `{"type":"welcome"}`
5. 구독 승인 확인 → `confirm_subscription`
6. 실제 제출로 엔드투엔드 검증 (풀이 수 90 → 92, 레이팅 1371 → 1372)

## 헛짚은 것들 (기록 목적)

### challengeable_id 혼동 — 4회 연속 실패

`<input data-type="code">` 의 `id` 를 `challengeable_id` 로 착각. 실제로는
`data-challengeable-id` 속성이 따로 있었다.

```
120804  challengeable_id = 14643  (data-challengeable-id)
        codes 키        = 49598  (input id)
```

잘못 보내도 **구독은 승인되고 테스트케이스도 정상 실행**되는데 결과 확정 단계에서만
`{"type":"error","msg":"내부적인 오류가 발생했습니다"}` 로 조용히 실패한다.
증상이 "16/16 통과인데 기록이 안 남음"이라 원인 추적이 어려웠다.

### 약점 진단 오류

프로그래머스 `partTitle` 만 보고 "DFS/BFS·탐욕법·힙·그래프가 통째로 비어 있다"고 진단.
그러나 `partTitle` 689개 중 알고리즘 유형은 **47개(7%)** 뿐이고 나머지는 대회명·난이도 묶음이었다.
백준 210문제에 solved.ac 태그를 붙여보니 정반대 —
그래프 이론 65 · BFS 35 · 우선순위 큐 11(평균 Gold II) · 세그먼트 트리 7(평균 Gold I).

**진짜 문제는 약점이 아니라 6개월 공백이었다.** 데이터 소스 하나만 보고 내린 진단이
얼마나 틀릴 수 있는지 보여주는 사례.

## 설계 전환

초기 설계는 "로컬 편집기 + 서버 능동 제출"이었으나, 문제 탐색·검색·설명 읽기를 CLI 로
재구현하는 것은 프로그래머스가 이미 잘하는 것을 더 나쁘게 만드는 일이라는 지적을 받고 전환.

전환의 결정적 근거는 **브로드캐스트 실측**이었다. ActionCable 스트림이 커넥션이 아니라
채널 파라미터로 스코프되므로, 서버가 같은 채널을 구독만 해도 브라우저가 발사한 결과가
그대로 들어온다. 별도 Python 프로세스가 쿠키만으로 접속해 브라우저 결과 4건을
동일하게 수신하는 것을 확인.

## 산출물

- `docs/programmers-protocol.md` (15장) — 프로토콜 사실관계
- `docs/superpowers/specs/2026-08-04-programmers-tracker-design.md` (13장) — 설계
- `CLAUDE.md` + `docs/development-rules.md` — 개발 규칙
- 저장소 구조 확정: programmers-tracker(public) + ps-records(public)
