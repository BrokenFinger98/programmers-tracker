---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [기록체계, 위키, 단일화]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-record-keeping-design.md]
---

# 결정 기록은 위키 ADR 단일 권위

## 맥락
결정이 `.harness/state/decisions.md`(요약 append)와 `wiki/decisions/`(상세 ADR) 두 곳에
쓰이고 있었다. 운영 첫날 실측: state 6건 vs 위키 5건 — **하루 만에 발산했다**
("저장소 2개" 결정이 위키에 없었다). 어긋난 두 기록은 어느 쪽이 진실인지 알 수 없게 한다.

## 검토한 선택지
- **A. 2단 유지** — state 는 요약 인덱스, 위키는 상세 (원설계)
- **B. decisions.md 폐지** — 위키 ADR 이 유일한 권위
- **C. 위키 결정 페이지 폐지** — state 파일만

## 결정
**B.** `.harness/state/decisions.md` 를 삭제하고 `docs/llm-wiki/wiki/decisions/`
(1건 1파일)만 남긴다. 세션 시작 시의 요약 스캔은 `index.md` 의 Decisions 절
(1결정 1줄)이 대신한다 — ingest 가 어차피 유지하는 파일이라 동기화 부담이 없다.

## 이유
이중 쓰기는 반드시 발산한다(첫날 실측). 프로토콜 사실을 `docs/programmers-protocol.md`
한 곳에만 두는 기존 원칙(위키 스키마 §5.1)의 일반화다: **한 사실은 한 곳에, 나머지는 참조.**
C 는 위키의 이중 목적(개발 기억 + 포트폴리오, 스키마 §0)을 버리게 되어 기각.

state 의 역할은 재정의된다 — **state = 위치**(goal·progress: 어디까지 왔나),
**위키 = 지식**(무엇을 왜 결정했나).

## 받아들인 비용
- 세션 시작 시 결정 전문이 아니라 제목만 스캔하게 된다. 충돌이 의심되면 해당 ADR 을
  한 번 더 열어야 한다 (1단계 추가)
- ADR 작성이 append 한 줄보다 무겁다 → 게이트는 존재만 검사하고 품질은 리뷰가 맡아
  stub ADR 로 시작하는 것을 허용한다

## 결과
_구현 후 갱신 — parity 대조(5건 상위집합 확인) 후 decisions.md 삭제._
