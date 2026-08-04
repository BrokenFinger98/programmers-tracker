---
type: synthesis
project: programmers-tracker
tags: [프로토콜, 리버스엔지니어링, actioncable]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md]
---

# 프로그래머스 프로토콜 규명 전말

> 사실관계의 유일한 출처는 `docs/programmers-protocol.md` 다. 이 페이지는 **어떻게 알아냈는가**를 남긴다.

## 왜 필요했나

프로그래머스는 채점 결과를 흘려보내고 끝낸다. 제출 이력 API 가 없다.
[[entities/baekjoonhub]] 도 정답일 때만 동작해 실패가 구조적으로 남지 않는다.
2025년 기록이 **총 시도 449회 / 푼 문제 43개** — 406번의 실패는 이미 소실됐다.

## 추적 경로

공개된 사례가 GitHub 전체에 없어 직접 개척해야 했다.

1. **번들 정독** — `application.js` 에서 `channel.perform("submit", {codes})` 발견.
   버튼은 껍데기였고 실체는 WebSocket 이었다.
2. **주소 확보** — `<meta name="action-cable-url">` → `wss://ws.programmers.co.kr:443/cable`
3. **핸드셰이크 실측** — `101 Switching Protocols` + `{"type":"welcome"}`
4. **구독 승인** — `confirm_subscription`
5. **엔드투엔드** — 실제 제출로 100점 통과, 레이팅 1371 → 1372

번들 정독이 결정적이었다. 네트워크 탭만 봤다면 WebSocket 프레임 안의 액션 이름과
페이로드 구조를 알기 어려웠을 것이다. **압축된 JS 에서 핸들러 이름(`handleSubmit`)으로
역추적하면 서버가 보낼 수 있는 메시지 타입 전량을 얻을 수 있다.**

## 막혔던 지점

[[concepts/verdict-classification]] 참조. 요약하면 `challengeable_id` 와 codes 키를
혼동해 4회 연속 실패했고, 증상이 "16/16 통과인데 기록이 안 남음"이라 추적이 어려웠다.
**잘못된 파라미터로도 구독이 승인되고 채점까지 도는 것**이 함정이었다.

교훈: 외부 프로토콜을 다룰 때 *부분 성공* 은 성공이 아니다. 끝까지 확인해야 한다.

## 파생 결정

[[decisions/2026-08-04-passive-broadcast-observation]] ·
[[decisions/2026-08-04-solve-in-web-editor]]
