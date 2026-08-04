---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [학습설계, MCP, 디버깅]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md]
---

# AI 디버거 제어 미채택

## 맥락
"JetBrains MCP 같은 걸로 디버깅 기능을 서버에 내재화할 수 있나"라는 질문.

## 검토한 선택지
- **A. Debugger MCP 를 붙여 AI 가 디버깅** — 중단점·스텝·변수 검사를 AI 가 조종
- **B. 붙이지 않고 사용자가 직접 디버깅**

## 결정
**B.**

## 이유
기술적으로는 가능하다. [Debugger MCP Server](https://plugins.jetbrains.com/plugin/29233-debugger-mcp-server)
가 8개 API 그룹 37개 도구로 중단점·세션·스텝·변수 검사·표현식 평가를 전부 노출한다.

그러나 **디버깅을 원한 이유가 "내가 디버그를 찍고 싶어서"였다.**
AI 가 디버거를 몰면 디버깅을 배우는 게 아니라 외주를 주는 것이 된다.

이는 **자동완성을 끄기로 한 판단과 정면으로 어긋난다.** 자동완성은 코드 몇 글자를
대신 쳐주는 것인데 그건 껐고, "문제를 어떻게 좁혀 들어가는가"라는 훨씬 핵심적인
능력은 AI 에 맡긴다면 방향이 반대다.

아키텍처 관점에서도 재구현할 이유가 없다. MCP 클라이언트는 여러 서버를 동시에 붙이므로
필요하면 별도 서버로 등록하면 된다. **작은 서버들이 각자 잘하는 것을 노출하고 클라이언트가
조합하는 것이 MCP 의 설계 철학**이다.

## 받아들인 비용
- 복잡한 버그에서 디버깅 시간이 더 걸린다 (의도한 비용)

## 결과
되돌릴 수 없는 결정이 아니다. 필요해지면 별도 MCP 서버로 붙인다.
