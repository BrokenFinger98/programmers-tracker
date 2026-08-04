# 프로그래머스 채점 프로토콜 리버스 엔지니어링

작성일: 2026-08-04
대상: `school.programmers.co.kr` 코딩테스트 연습 (`/learn/courses/30/lessons/{lessonId}`)

## 요약

프로그래머스의 "제출 후 채점하기"는 REST API가 아니라 **Rails ActionCable WebSocket**이다.
버튼은 `channel.perform("submit", {codes})`를 부르는 껍데기일 뿐이며,
동일한 메시지를 직접 보내면 브라우저 UI 없이 채점이 가능하다.

**엔드투엔드 검증 완료** — 알고리즘 문제와 SQL 문제 양쪽 모두 로컬 코드를 WebSocket으로
제출해 100점 통과를 확인했다 (풀이 수 90 → 92, 레이팅 1371 → 1372).

## 1. 접속 정보

| 항목 | 값 |
|---|---|
| WebSocket | `wss://ws.programmers.co.kr:443/cable` |
| 서브프로토콜 | `actioncable-v1-json` |
| 인증 | `_session_production` 쿠키 (HttpOnly) |

WebSocket 주소는 문제 페이지의 `<meta name="action-cable-url">`에서 읽는다.

## 2. 문제 유형별 채널

`data-challengeable-type` 속성으로 판별한다.

| 유형 | `challengeable_type` | 채널 | 검증 |
|---|---|---|---|
| 알고리즘 | `algorithm` | `Challenge::AlgorithmChannel` | ✅ |
| SQL | `database` | `Challenge::DatabaseChannel` | ✅ |

번들에는 `Challenge::SqlChannel`도 존재하나, 코딩테스트 연습의 SQL 문제는
전부 `database` 타입이므로 `DatabaseChannel`을 쓴다. `SqlChannel`은 미검증.

그 외 존재하는 채널(전부 미검증): `Challenge::ApiChannel`, `Challenge::DataScienceChannel`,
`Challenge::EssayChannel`, `Challenge::QuizChannel`, `Challenge::WebChannel`,
`Challenge::VscodeChannel`, `Live::AlgorithmChannel`, `TryoutChannel` 등.

구독 파라미터 형태는 모든 채널이 동일하며 `channel` 이름만 다르다.

## 3. 필요한 식별자

문제 페이지(`?language=<lang>`)의 HTML에서 전부 추출된다. **로그인 불필요.**

| 이름 | 출처 | 언어 의존 |
|---|---|---|
| `lesson_id` | URL 경로 / `data-lesson-id` | ✗ |
| `challengeable_id` | `data-challengeable-id` 속성 | **✗ 언어 무관** |
| `challengeable_type` | `data-challengeable-type` 속성 | ✗ |
| codes 키 | `<input data-type="code">` 의 `id` 속성 | **✓ 언어별로 다름** |

실측:

```
120804 (알고리즘)  challengeable_id=14643 고정
                   codes키: java=49598, python3=49601, cpp=49595, kotlin=49600
 59036 (SQL)       challengeable_id=2754 고정
                   codes키: mysql=86909, oracle=86910
```

> **함정**: `challengeable_id`와 codes 키는 서로 다른 값이다.
> codes 키를 `challengeable_id`로 잘못 보내면 **구독은 승인되고 테스트케이스도 정상 실행되지만**,
> 결과 확정 단계에서 `{"type":"error","msg":"내부적인 오류가 발생했습니다"}`로 조용히 실패한다.
> 실제로 이 혼동 때문에 초기 시도가 반복 실패했다. 디버깅하기 고약한 함정이므로 주의.

지원 언어 13종: `c, cpp, csharp, go, java, javascript, kotlin, python3, ruby, scala, swift, mysql, oracle`

## 4. 메시지 시퀀스

```jsonc
// ← 서버
{"type":"welcome"}

// → 구독
{"command":"subscribe","identifier":"{\"channel\":\"Challenge::AlgorithmChannel\",
  \"challengeable_type\":\"algorithm\",\"challengeable_id\":14643,
  \"language\":\"java\",\"lesson_id\":120804}"}

// ← 승인
{"identifier":"…","type":"confirm_subscription"}

// → 채점 요청
{"command":"message","identifier":"<위와 동일>",
 "data":"{\"action\":\"submit\",\"codes\":{\"49598\":\"class Solution { … }\"}}"}
```

`identifier`는 구독 시 보낸 문자열과 **바이트 단위로 동일**해야 한다 (ActionCable이 키로 사용).

`{"type":"ping"}` 은 3초마다 오는 하트비트이므로 무시한다.

모든 응답은 `{"identifier":"…","message":{…}}` 로 감싸여 온다.
`message.action` 은 요청한 액션, `message.type` 이 세부 종류다.

## 5. 알고리즘 문제 응답 (실측)

```jsonc
{"action":"submit","type":"start","msg":"채점을 시작합니다."}

{"action":"submit","type":"test_group","category":"correctness",
 "testcaseIds":[154893,154894,…],"msg":"정확성  테스트","subtasks":[]}

// 테스트케이스마다 1건. 병렬 채점이라 순서가 뒤섞여 도착 → testcaseId로 정렬 필요
{"action":"submit","type":"testcase","testcaseId":154894,"testcasesCount":16,
 "passed":false,"msg":"실패 (0.01ms, 75.3MB)",
 "run_time":"0.01","memory_size":78950400}

{"action":"submit","type":"result_lesson_challenge",
 "passed":true,
 "scores":[{"name":"정확성","score":"100.0"}],   // 효율성 테스트가 있으면 항목 2개
 "userScore":"100.0","perfectScore":"100.0",
 "challengeableId":14643,"language":"java",
 "isNewRating":true,"oldUserRating":1371,"newUserRating":1372,
 "finishModalLink":"/learn/courses/30/lessons/120804/solution_groups?language=java",
 "surveyUrl":"/custom_form_groups/4714"}

{"action":"submit","type":"finish"}
```

**오답도 동일한 시퀀스**로 오며 `passed:false`, `userScore`에 부분점수가 담긴다.
실측: 16개 중 1개 통과 → `"userScore":"1.4"` (테스트케이스별 배점이 균등하지 않음).

## 6. SQL 문제 응답 (실측)

**알고리즘과 필드 명명 규칙이 다르다. 파서는 양쪽을 모두 처리해야 한다.**

### submit

```jsonc
{"action":"submit","type":"start","testcase_ids":[5438],"msg":"채점을 시작합니다.",
 "challengeable_type":"database","challengeable_id":2778}

{"action":"submit","type":"testcase","testcase_id":5438,"passed":true,"msg":"통과",
 "challengeable_type":"database","challengeable_id":2778}

{"action":"submit","type":"result_lesson_challenge",
 "userScore":"100.0","perfectScore":"100.0","passed":true,
 "finishModalBtnText":"다음 문제 풀기",
 "finishModalLink":"/learn/courses/30/lessons/273710",
 "challengeable_type":"database","challengeable_id":2778}
```

### run (예제 실행 — 제출 이력에 남지 않음)

```jsonc
{"action":"run","type":"start","testcase_ids":[5437],
 "challengeable_type":"database","challengeable_id":2778}

{"action":"run","type":"finish","testcase_id":5437,"passed":true,"msg":null,
 "returned_rows":"{\"columns\":[\"USERS\"],\"data\":[[4]]}",
 "challengeable_type":"database","challengeable_id":2778}
```

`returned_rows`는 **이중 인코딩된 JSON 문자열**이다. 한 번 더 파싱해야 테이블이 나온다.
`run`과 `submit`은 서로 다른 테스트케이스를 쓴다 (예제 5437 vs 채점 5438).

### 알고리즘 대비 차이 요약

| 항목 | 알고리즘 | SQL |
|---|---|---|
| 필드 명명 | camelCase (`testcaseId`) | **snake_case** (`testcase_id`) |
| `test_group` 메시지 | 있음 | **없음** (`start`에 `testcase_ids` 포함) |
| 케이스별 시간/메모리 | `run_time`, `memory_size` | **없음** |
| `scores` 배열 | 있음 | **없음** |
| 레이팅 변화 | `isNewRating`, `old/newUserRating` | **없음 — 레이팅에 영향 없음** |
| `finish` 메시지 | 있음 | **없음 — `result_lesson_challenge`에서 끝** |
| 타입/ID 에코 | 결과에만 | **모든 메시지에 포함** |
| 테스트케이스 수 | 16개 (예시) | 1개 |

> **주의**: SQL은 `finish`를 보내지 않는다. `finish` 대기로 종료 판정하면 무한 대기한다.
> `result_lesson_challenge` 수신을 종료 조건에 포함해야 한다.

## 7. 실패 유형 판별 (실측)

### submit 경로 — `msg` 문자열로만 판별 가능

`testcase` 메시지의 `msg`가 유일한 단서다. `exitCode`나 `stderr`는 **오지 않는다.**

| 유형 | `msg` | `run_time` / `memory_size` |
|---|---|---|
| 통과 | `"통과 (0.01ms, 85.2MB)"` | 값 있음 |
| 오답 | `"실패 (0.01ms, 75.3MB)"` | 값 있음 |
| 런타임 에러 | `"실패 (런타임 에러)"` | **`null`** |
| 컴파일 에러 | `"실패 (런타임 에러)"` | **`null`** |
| 시간 초과 | `"실패 (시간 초과)"` | **`null`** |

> **컴파일 에러와 런타임 에러는 submit 응답만으로 구분할 수 없다.**
> 둘 다 `"실패 (런타임 에러)"`로 보고된다. 구분하려면 `run` 액션을 써야 한다.

세 경우 모두 `result_lesson_challenge`는 `userScore: "0.0"`, `passed: false`로 오고
`finish`까지 정상적으로 도착한다.

### run 경로 — 에러 전문을 준다

```jsonc
// 예제 테스트케이스가 start에 통째로 실려온다 (본문 HTML 파싱 불필요!)
{"action":"run","type":"start",
 "testcases":[{"input":"3, 2","output":"1"},{"input":"10, 5","output":"0"}],
 "hideResult":false,"challengeable_type":"algorithm","challengeable_id":14650}

// 컴파일 에러
{"action":"run","type":"error","index":0,
 "msg":"/Solution.java:3: error: &#39;;&#39; expected<br/>        int year = 2022 - age + 1<br/>                                 ^<br/>1 error<br/>"}

// 런타임 예외 — 스택 트레이스 전문
{"action":"run","type":"error","index":1,
 "msg":"Exception in thread &quot;main&quot; java.lang.ArrayIndexOutOfBoundsException: Index 1000 out of bounds for length 5<br/>\tat Solution.solution(Unknown Source)<br/>\tat SolutionTest.lambda$main$0(Unknown Source)<br/>…"}
```

- `msg`는 **HTML 이스케이프**되어 있다. 줄바꿈은 `<br/>`, 따옴표는 `&quot;` / `&#39;`.
  표시하려면 언이스케이프 + `<br/>` → `\n` 치환이 필요하다.
- `index`는 **몇 번째 예제에서 터졌는지**를 가리킨다 (0-based).
- 알고리즘 `run`의 `input`은 `"3, 2"` 처럼 쉼표로 이어붙인 인자 문자열이다.

**`run`의 `start`에 담긴 `testcases`가 로컬 스캐폴딩의 최적 경로다.**
문제 본문 HTML을 파싱해 표를 긁을 필요 없이 예제 입출력을 구조화된 형태로 바로 받는다.

## 8. 전체 type 카탈로그 (번들 추출)

```
submit : start, test_group, testcase, result_without_score, result,
         result_lesson_challenge, finish, notice, paused, error
run    : start, testcase, result, finish, notice
save   : result, failed_git, failed_save
reset  : completed, failed
```

`submit` 응답에 등장 가능한 필드:
`category, challengeableId, exitCode, finishCondition, isNewRating, msg,
newUserRating, oldUserRating, passed, passingScore, perfectScore, scores,
scoringUnit, stderr, testcaseId, testcaseIds, type, userScore, run_time, memory_size`

알고리즘 `run` 응답 필드 (번들 기준):
`exitCode, hideResult, index, msg, passed, passedCount, stdout, stderr, testcases, totalCount, type`

실측에서는 에러 발생 시 `type:"error"` + `msg`(전문)로 끊기며, `stdout`은 함께 오지 않았다.
정상 실행 시 `stdout`이 오는지는 미검증.

### 기타 액션

- `run` — 예제 테스트케이스만 실행. 제출 이력에 남지 않음. ✅ 알고리즘·SQL 양쪽 검증
- `save` — 코드 자동저장. 응답에 `challengeableId`가 실려오므로 id 검증에 활용 가능. ✅ 검증
- `stop` / `reset` / `finish` — 실행 중단 / 초기화 / 시험 종료. 미검증

## 9. 문제 목록 API

```
GET /api/v2/school/challenges/?perPage=100&page=1
GET /api/v2/school/challenges/?perPage=100&statuses[]=solved&page=1
```

**인증 없이도 동작**하며, 로그인 시 `status`가 `solved`/`unsolved`로 갈린다.

```jsonc
{"page":1,"perPage":100,"totalPages":7,"totalEntries":689,
 "languages":["c","cpp","csharp","go","java","javascript","kotlin",
              "python3","ruby","scala","swift","mysql","oracle"],
 "result":[{"id":468381,"title":"기차 선로","partTitle":"2025 카카오 하반기 2차",
            "level":3,"finishedCount":310,"acceptanceRate":4,
            "status":"unsolved","openedAt":"…","aiCommentable":false}]}
```

전체 689문제. `partTitle`이 유형/출처(해시, DFS/BFS, SELECT, 2024 KAKAO …)라
약점 분석 축으로 쓸 수 있다.

- 레벨 분포: `Lv0 240, Lv1 119, Lv2 155, Lv3 109, Lv4 45, Lv5 21`
- SQL 문제 106개: `SELECT 33, GROUP BY 24, String·Date 19, JOIN 12, SUM·MAX·MIN 10, IS NULL 8`

## 10. 브로드캐스트 — 수동 관찰 (실측)

**같은 `identifier`를 구독한 모든 클라이언트가 동일한 메시지를 동시에 받는다.**
ActionCable 스트림이 커넥션이 아니라 채널 파라미터 기준으로 스코프되기 때문이다.

즉 **서버가 채널을 구독만 해두면, 사용자가 브라우저에서 누른 채점 결과가
그대로 서버에도 흘러들어온다.** 프록시도 브라우저 확장의 트래픽 가로채기도 필요 없다.

### 검증 1 — 같은 페이지 내 소켓 2개

소켓 B가 구독만 하고 대기, 소켓 A가 `run` 발사:

```
A_received: start@2050  testcase@3150  testcase@3150  result@3150
B_received: start@2050  testcase@3150  testcase@3150  result@3150
```

동일 메시지, 동일 시각.

### 검증 2 — 별도 프로세스 (핵심)

Python 프로세스가 `_session_production` 쿠키만으로 접속해 구독하고,
**브라우저에서** `run`을 발사:

```
[ 0.40s] CONNECTED subprotocol=actioncable-v1-json
[ 0.43s] CONFIRM_SUBSCRIPTION       ← 브라우저 아닌 프로세스도 인증 통과
[12.98s] BROADCAST action=run type=start
[13.99s] BROADCAST action=run type=testcase
[14.07s] BROADCAST action=run type=testcase
[14.07s] BROADCAST action=run type=result
```

브라우저가 받은 4건과 정확히 일치. **수동 관찰 아키텍처가 성립한다.**

접속 시 필요한 헤더:

```
Cookie: _session_production=…; tracking_id=…; locale=…; timezone=…
Origin: https://school.programmers.co.kr
Sec-WebSocket-Protocol: actioncable-v1-json
```

### 한계 — 와일드카드 구독이 없다

`identifier`는 글자 단위로 일치해야 하며 `challengeable_id: *` 같은 패턴 구독이 없다.
"이 사용자의 모든 제출"을 구독하는 방법도 없다. 따라서 **서버는 사용자가 어떤 문제를
열었는지 미리 알아야** 해당 채널을 구독할 수 있다.

문제 689개 × 언어 13종이라 전부 구독하는 것은 비현실적이다.

### 결과 메시지에 코드는 없다 — 별도로 회수한다

브로드캐스트에는 소스코드가 포함되지 않는다. 대신 **로그인 상태로 문제 페이지를 받으면
사용자가 마지막으로 저장한 코드**가 들어있다 (편집 중 `save` 액션으로 자동저장되며,
`submit` 시에도 저장된다).

```
GET /learn/courses/30/lessons/{lessonId}?language={lang}   (인증 필요)
  → <input data-type="code" value="<저장된 내 코드>">
  → <input id="initial_code_{id}" value="<문제 초기 골격>">   비교용
```

실측:

```
59036  savedCode: "SELECT ANIMAL_ID, NAME FROM ANIMAL_INS WHERE INTAKE_CONDITION = 'Sick'…"
120804 savedCode: "class Solution { public int solution(int num1, int num2) { return num1 * num2; } }"
```

## 11. 제출 이력 API는 없다

지나간 제출을 조회할 방법이 없다. 번들의 API 경로를 전수 확인한 결과 제출 관련
엔드포인트는 `/essay_submissions`, `/quiz_submissions`, `/skill_checks/…/source.js` 뿐이며
전부 다른 제품(에세이·퀴즈·역량진단)용이다. 코딩테스트 연습용은 존재하지 않는다.

조회 가능한 것은 두 가지뿐이다.

```
GET /api/v2/school/challenges/?statuses[]=solved      풀었다/못 풀었다
GET /api/v1/main/open-challenge-activities?year=2026  날짜별 시도 횟수 집계
```

후자의 응답:

```jsonc
{"solvedChallengeCount":43,"attemptedChallengeCount":46,"attemptTotalCount":449,
 "attempts":[["2025-01-02",35],["2025-01-03",9], …]}   // 날짜와 횟수뿐
```

문제도 코드도 성패도 없다. **채점 결과는 그 순간에 잡지 않으면 영구히 소실된다.**

## 12. 문제 본문 · 입출력 예제

`GET /learn/courses/30/lessons/{lessonId}?language=java` (인증 불필요)

- 본문: `div.guide-section-description > div.markdown`
- 입출력 예제: 본문 내 `<table>` → `[['num1','num2','result'], ['2','3','-1'], …]`
- 언어별 초기 코드: `<input data-type="code" data-language="java" value="…">`
- SQL은 본문에 테이블 스키마(컬럼명·타입·Nullable)가 표로 들어있다

로컬 스캐폴딩에 필요한 재료가 전부 나온다.

## 13. 주의사항 (실측 기반)

1. **채점 요청은 열려 있는 페이지와 무관하다.** 12943 페이지에서 120803을 제출해도 정상 동작한다.
   커넥션 하나로 임의의 문제를 채점할 수 있다.

2. **같은 문제에 연속 제출하면 서버 상태가 꼬인다.** 동일 코드 재제출 시 캐시된 결과가
   1초 내에 돌아오고 결과 확정이 `error`로 실패했다. 제출 간 간격을 두어야 한다.

3. **동시 제출 금지.** 같은 문제에 WebSocket을 겹쳐 열면 `error`가 발생한다.
   원 UI도 `channel.isIdle()`로 중복 실행을 막는다.

4. `enabledDailyLimit` / `dailyLimitType` / `dailyLimitProgress` 필드가 존재한다.
   연습문제에는 적용되지 않는 것으로 보이나 데브코스 과제에는 일일 제한이 있을 수 있다.

5. **채점 소요 시간 실측 — 클라이언트 타임아웃 설계에 중요:**

   | 상황 | 소요 |
   |---|---|
   | 알고리즘 정상 채점 (Java, 16케이스) | 6~9초 |
   | 알고리즘 런타임/컴파일 에러 | 4~7초 |
   | **알고리즘 시간 초과 (16케이스)** | **약 87초** |
   | SQL 채점 | 1~2초 |
   | `run` (예제 실행) | 1~2초 |

   시간 초과 케이스가 압도적으로 느리다. 클라이언트 타임아웃은 **최소 120초**를 잡아야
   정상적인 시간초과 판정을 중간에 끊지 않는다.

## 14. 미검증 항목

- 효율성 테스트가 있는 문제의 `scores` 배열 2항목 형태
- 알고리즘 `run` 정상 실행 시 `stdout` 회수 여부
- `Challenge::SqlChannel` (연습문제는 전부 `database` 타입이라 미사용)
- 레이트리밋의 정확한 규칙
- Oracle 제출
- 메모리 초과(`메모리 초과`) 메시지 — 미유발

## 15. 검증 로그

| # | 문제 | 유형 | 액션 | 결과 |
|---|---|---|---|---|
| 1 | 120803 | 알고리즘 | submit | 오답 → `result_lesson_challenge` 수신, 1.4점 (id 49587 — 잘못된 값) |
| 2~5 | 120803 | 알고리즘 | submit | 정답 → 16/16 통과하나 결과 확정 `error` (id 혼동 + 반복 제출) |
| 6 | **120804** | 알고리즘 | submit | **100점, 레이팅 1371→1372, `finish` 정상** (id 14643) |
| 7 | **131528** | **SQL** | submit | **100점, `finish` 없이 종료** (id 2778) |
| 8 | 131528 | SQL | `run` | `returned_rows` 수신, 제출 이력 없음 |
| 9 | 120820 | 알고리즘 | submit | 컴파일 에러 → 12케이스 전부 `"실패 (런타임 에러)"`, 0점 |
| 10 | 120810 | 알고리즘 | submit | 런타임 예외 → 14케이스 전부 `"실패 (런타임 에러)"`, 0점 |
| 11 | 120805 | 알고리즘 | submit | 무한 루프 → 16케이스 전부 `"실패 (시간 초과)"`, 0점, **87초 소요** |
| 12 | 120820 | 알고리즘 | `run` | **컴파일러 출력 전문 회수** (`/Solution.java:3: error: ';' expected`) |
| 13 | 120810 | 알고리즘 | `run` | **스택 트레이스 전문 회수** (`ArrayIndexOutOfBoundsException`) |

풀이 수 90 → 92 증가로 서버 반영 확인. 레이팅 1371 → 1372.
9~11번은 의도적 실패 제출이므로 해당 문제들은 미해결 상태로 남아 있다.
