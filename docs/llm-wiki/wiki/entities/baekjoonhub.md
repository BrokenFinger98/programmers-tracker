---
type: entity
project: programmers-tracker
tags: [baekjoonhub, 선행도구]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md]
---

# BaekjoonHub

풀이를 GitHub 에 자동 푸시하는 Chrome 확장. 이 프로젝트가 대체하려는 선행 도구.

## 구조적 한계

```js
// scripts/programmers/programmers.js:46
else if (getSolvedResult().includes('정답')) {   // ← 2초 폴링, '정답' 일 때만
```

2초마다 결과 모달을 폴링해 **"정답"일 때만** CodeMirror 내용을 긁어 올린다.
따라서 오답·시간초과·시도 횟수가 **구조적으로 남을 수 없다.**

결과도 DOM(`td.result.passed`)에서 스크래핑하므로 원본 스트림보다 정보가 적다.

## 실제 유실량

계정 기록 대조 결과 프로그래머스 91문제 중 GitHub 에는 56문제만 있었다.
2025년은 총 시도 449회 / 푼 문제 43개 — **406번의 실패가 기록되지 않았다.**

이 프로젝트가 완성되면 제거한다.

→ [[syntheses/protocol-reverse-engineering]]
