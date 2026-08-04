---
type: concept
project: programmers-tracker
tags: [actioncable, 아키텍처, websocket]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md]
---

# ActionCable 브로드캐스트 수동 관찰

## 원리

Rails ActionCable 의 스트림은 **커넥션이 아니라 채널 파라미터로 스코프**된다.
같은 `identifier` 를 구독한 모든 클라이언트가 동일한 메시지를 동시에 받는다.

따라서 사용자가 브라우저에서 채점을 누르면, **같은 채널을 구독해 둔 다른 프로세스에도
결과가 그대로 흘러든다.** 가로챌 대상이 없으므로 MITM 프록시도 확장 후킹도 불필요하다.

## 실측 (2026-08-04)

별도 Python 프로세스가 세션 쿠키만으로 접속 → 구독 → 브라우저에서 `run` 발사:

```
[0.43s] CONFIRM_SUBSCRIPTION     ← 비브라우저 프로세스도 인증 통과
[12.98s] BROADCAST run/start
[13.99s] BROADCAST run/testcase
[14.07s] BROADCAST run/testcase
[14.07s] BROADCAST run/result
```

브라우저가 받은 4건과 정확히 일치.

## 한계 — 와일드카드가 없다

`identifier` 는 글자 단위로 일치해야 하며 패턴 구독이 없다. "이 사용자의 모든 제출"을
구독할 방법도 없다. 따라서 **서버는 사용자가 어떤 문제를 열었는지 미리 알아야** 한다.
문제 689개 × 언어 13종이라 전부 구독하는 것은 비현실적 — 센서가 필요한 이유다.

## 결과 메시지에 코드는 없다

브로드캐스트에는 소스코드가 없다. 대신 로그인 상태로 문제 페이지를 받으면 사용자가
마지막으로 저장한 코드가 들어있다 (`<input data-type="code" value="...">`).

→ [[decisions/2026-08-04-passive-broadcast-observation]]
