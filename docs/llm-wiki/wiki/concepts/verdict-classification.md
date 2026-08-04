---
type: concept
project: programmers-tracker
tags: [프로토콜, verdict, 디버깅패턴]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md]
---

# verdict 판별과 그 함정

## submit 만으로는 컴파일 에러를 구분할 수 없다

`testcase` 메시지의 `msg` 문자열이 유일한 단서이며 `exitCode`·`stderr` 는 오지 않는다.

| 유형 | `msg` |
|---|---|
| 오답 | `실패 (0.01ms, 75.3MB)` |
| **런타임 에러** | `실패 (런타임 에러)` |
| **컴파일 에러** | `실패 (런타임 에러)` ← 동일 |
| 시간 초과 | `실패 (시간 초과)` |

컴파일 에러와 런타임 에러가 같은 문자열로 온다. 구분하려면 `run` 액션이 필요하다 —
거기서는 컴파일러 출력과 스택 트레이스 전문이 온다.

**그래서 `run` 을 반드시 기록한다.** 커밋은 하지 않지만 에러 전문의 유일한 출처다.

## 조용한 실패 — challengeable_id 혼동

가장 오래 막힌 지점. 페이지에 비슷한 숫자가 둘 있다.

```
data-challengeable-id="14643"   ← 구독 식별자
<input id="49598" data-type="code">  ← codes 페이로드 키
```

codes 키를 `challengeable_id` 로 잘못 보내면 **구독이 승인되고 테스트케이스도 정상
실행되지만** 결과 확정에서만 `{"type":"error"}` 로 실패한다.
증상이 "16/16 통과인데 기록이 안 남음"이라 원인 추적이 어려웠다.

> **일반화**: 외부 프로토콜에서 *부분 성공* 은 성공이 아니다. 중간 단계가 통과했다고
> 파라미터가 옳다고 결론내면 안 된다.

## 시간 초과는 87초 걸린다

정상 채점 6~9초, 시간초과 채점 **87초**. 클라이언트 타임아웃을 60초로 잡으면
정상적인 시간초과 판정을 중간에 끊는다. 최소 120초.

→ [[syntheses/protocol-reverse-engineering]]
