---
type: source
project: programmers-tracker
tags: [기록체계, 위키, git훅]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-record-keeping-design.md]
---

# 2026-08-04 기록 보존 체계 설계 세션 요약

## 핵심 주장
1. auto-compact 는 디스크 transcript 를 지우지 않는다 — 잃는 것은 원본이 아니라 **증류**다.
2. 넛지만으로 증류는 일어나지 않는다 (전역 inbox 75건/2.4GB/월, ingest 0회 실측).
3. 이중 기록은 하루 만에 발산한다 (state 6건 vs 위키 ADR 5건 실측).
4. 훅은 설정 계층 간 merge — 프로젝트가 전역 훅을 끌 수 없다.
5. 전역 아카이브 훅은 cwd 무관 발화 — 프로젝트 위키를 레포에 둬도 raw 는 개인 PC 에 쌓인다 (실증).

## 이 소스가 갱신한 페이지
[[decisions/2026-08-04-two-public-repos]] ·
[[decisions/2026-08-04-decisions-live-in-wiki]] ·
[[decisions/2026-08-04-wiki-push-gate]] ·
[[decisions/2026-08-04-global-project-wiki-split]]
