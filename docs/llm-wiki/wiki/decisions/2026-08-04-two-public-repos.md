---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [저장소, 공개전략]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md, raw/sessions/2026-08-04-record-keeping-design.md]
---

# 저장소 2개 · 양쪽 public

## 맥락
코드·설계 문서·풀이 기록을 어디에 어떻게 둘 것인가. 풀이 기록에는 실패 이력·힌트
사용 단계까지 포함되고, 저장소는 포트폴리오를 겸한다.

## 검토한 선택지
- **A. 단일 레포** — 코드와 기록을 한 곳에
- **B. 2레포 · 양쪽 public** — `programmers-tracker`(코드+설계+위키) / `ps-records`(기록)
- **C. 2레포 · 기록만 private** — 실패 이력 비공개

## 결정
**B.**

## 이유
설계 문서를 코드와 분리하면 반드시 어긋나므로 한 레포에 둔다. 기록은 성격(개인 데이터)
과 수명(계정 단위)이 코드와 달라 분리한다. `ps-records` 를 public 으로 두는 것은
**실패 이력·힌트 사용 단계까지 포함한 성장 서사가 자산**이라는 판단이다.

## 받아들인 비용
- 실패까지 공개하는 심리 비용
- 자격증명·개인정보 관리가 한층 엄격해야 한다 — **gitignore 예외 없음**
  (세션 쿠키·이메일은 어떤 경우에도 커밋 금지, [[decisions/2026-08-04-global-project-wiki-split]] 의
  raw 원문 반입 금지 규칙과 같은 축)

## 결과
2026-08-04 두 레포 생성. 이 결정은 `.harness/state/decisions.md` 에만 있다가
[[decisions/2026-08-04-decisions-live-in-wiki]] 에 따라 ADR 로 이관됐다 —
이중 기록이 첫날부터 발산한 실례(6건 vs 5건)가 바로 이 항목이다.
