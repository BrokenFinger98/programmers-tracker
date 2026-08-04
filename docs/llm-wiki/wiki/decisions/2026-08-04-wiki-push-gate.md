---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [기록체계, git훅, 강제장치]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-record-keeping-design.md]
---

# push 게이트로 증류를 강제한다 (native pre-push)

## 맥락
세션 원문은 auto-compact 후에도 디스크에 남는다(230MB transcript 실측 — compact 는
in-context 만 압축). 따라서 잃는 것은 원본이 아니라 **증류**다. 전역 위키 실측:
리마인더가 매 세션 떴는데도 한 달간 75개 세션(2.4GB) 적체, ingest 0회.
**넛지만으로는 증류가 일어나지 않고**, 증류는 맥락이 신선할 때가 가장 싸다.

## 검토한 선택지
강제 수위 — **A. 넛지만**(전역에서 실패 실증) · **B. commit 단위 차단**(토큰 비용,
같은 내용 반복 merge 로 페이지 오염) · **C. 브랜치/push 단위 차단**
메커니즘 — **㉮ Claude PreToolUse deny** · **㉯ git native pre-push**
탈출구 — **ⓐ permissionDecision "ask"**(흔적이 안 남음) · **ⓑ 커밋 트레일러**(감사 가능)

## 결정
**C + ㉯ + ⓑ.** `.githooks/pre-push` 가 push 범위에 `docs/llm-wiki/` 변경이 없으면
차단한다. 예외는 `Wiki-Skip: <사유>` 트레일러 — 사유가 히스토리에 감사 흔적으로 남는다.

## 이유
㉯ 가 ㉮ 를 3축에서 이긴다: **터미널 직접 push 도 잡고**, push 범위를 추정(HEAD 기준)이
아니라 **stdin 으로 정확히 받으며**, Claude Code 전용이 아니라 **도구·플랫폼 중립**이다
(GitHub/GitLab, 어떤 AI 도구든). 차단 시 stderr 는 Bash 도구 결과로 모델에게 보이므로
"차단 → /wiki-ingest → 재시도" 흐름이 성립한다.

fail-open 원칙: git 오류·예상 밖 입력은 전부 통과. 이 게이트는 **무의식적 누락 방지
장치이지 우회 방지 장치가 아니다.**

## 받아들인 비용
- 클로너 설치 마찰 — `git config core.hooksPath .githooks` 1커맨드 (Claude Code 는
  SessionStart 훅이 자동 설치)
- 트레일러 남용 가능 — 기계는 존재만 보고, 남용은 PR 리뷰·/wiki-lint 가 잡는다
- 관행 밖 push 형태(비 HEAD refspec 등)는 통과한다 — 의도된 한계

## 결과
_구현 후 갱신 — 이 게이트를 도입한 브랜치 자체가 첫 실전 통과 사례(dogfooding)._
