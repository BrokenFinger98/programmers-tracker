---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [아키텍처, actioncable]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md]
---

# 채점 연동 = 브로드캐스트 수동 관찰

## 맥락
프로그래머스 채점 결과를 로컬에 기록해야 한다. 제출 이력 API 가 없어 **일어나는 순간에
잡지 않으면 영구 소실**된다.

## 검토한 선택지
- **A. 브라우저 자동화** — Claude 가 Chrome 을 조종해 제출·결과 수집
- **B. 서버 능동 제출** — 로컬 코드를 서버가 WebSocket 으로 직접 제출
- **C. MITM 프록시** — 브라우저 트래픽을 복호화해 관찰
- **D. 확장 트래픽 후킹** — content script 가 WebSocket 을 가로챔
- **E. 브로드캐스트 수동 관찰** — 서버가 같은 채널을 구독해 듣기만

## 결정
**E.** 서버는 프로그래머스에 아무것도 보내지 않고 구독만 한다.

## 이유
ActionCable 스트림이 커넥션이 아니라 채널 파라미터로 스코프됨을 실측 확인
([[concepts/actioncable-broadcast-observation]]). 별도 프로세스가 쿠키만으로 접속해
브라우저 발사 결과 4건을 동일하게 수신했다.

**가로챌 대상이 없으므로** C·D 가 불필요해진다. A 는 제출마다 AI 세션이 필요하고,
B 는 사용자가 웹에서 푸는 워크플로와 맞지 않는다.

## 받아들인 비용
- 어떤 문제를 열었는지 알려줄 **센서 확장**이 필요하다 (와일드카드 구독 불가)
- 서버가 꺼져 있으면 그 제출은 놓친다 (복구 불가)
- 코드가 결과에 실려오지 않아 문제 페이지를 따로 조회해야 한다

## 결과
_구현 후 갱신_
