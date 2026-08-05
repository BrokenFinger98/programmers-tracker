# Programmers Grading Protocol Reverse Engineering

Date: 2026-08-04
Target: `school.programmers.co.kr` coding test practice (`/learn/courses/30/lessons/{lessonId}`)

## Summary

Programmers' "submit and grade" is not a REST API but a **Rails ActionCable WebSocket**.
The button is just a shell that calls `channel.perform("submit", {codes})`,
and sending the same message directly grades the code without any browser UI.

**End-to-end verified** — for both an algorithm problem and a SQL problem, local code was
submitted over the WebSocket and passed with a score of 100 (solved count 90 → 92, rating 1371 → 1372).

## 1. Connection Info

| Item | Value |
|---|---|
| WebSocket | `wss://ws.programmers.co.kr:443/cable` |
| Subprotocol | `actioncable-v1-json` |
| Auth | `_session_production` cookie (HttpOnly) |

The WebSocket URL is read from `<meta name="action-cable-url">` on the problem page.

## 2. Channels by Problem Type

Determined by the `data-challengeable-type` attribute.

| Type | `challengeable_type` | Channel | Verified |
|---|---|---|---|
| Algorithm | `algorithm` | `Challenge::AlgorithmChannel` | ✅ |
| SQL | `database` | `Challenge::DatabaseChannel` | ✅ |

The bundle also contains `Challenge::SqlChannel`, but every SQL problem in the coding
test practice is of type `database`, so `DatabaseChannel` is used. `SqlChannel` is unverified.

Other channels that exist (all unverified): `Challenge::ApiChannel`, `Challenge::DataScienceChannel`,
`Challenge::EssayChannel`, `Challenge::QuizChannel`, `Challenge::WebChannel`,
`Challenge::VscodeChannel`, `Live::AlgorithmChannel`, `TryoutChannel`, etc.

The subscription parameter shape is identical across all channels; only the `channel` name differs.

## 3. Required Identifiers

All extracted from the HTML of the problem page (`?language=<lang>`). **No login required.**

| Name | Source | Language-dependent |
|---|---|---|
| `lesson_id` | URL path / `data-lesson-id` | ✗ |
| `challengeable_id` | `data-challengeable-id` attribute | **✗ language-independent** |
| `challengeable_type` | `data-challengeable-type` attribute | ✗ |
| codes key | `id` attribute of `<input data-type="code">` | **✓ differs per language** |

Measured:

```
120804 (알고리즘)  challengeable_id=14643 고정
                   codes키: java=49598, python3=49601, cpp=49595, kotlin=49600
 59036 (SQL)       challengeable_id=2754 고정
                   codes키: mysql=86909, oracle=86910
```

> **Trap**: `challengeable_id` and the codes key are different values.
> If you mistakenly send the codes key as `challengeable_id`, **the subscription is confirmed
> and the testcases even run normally**, but the result-finalization step silently fails with
> `{"type":"error","msg":"내부적인 오류가 발생했습니다"}` ("an internal error occurred").
> This confusion actually caused repeated failures in early attempts. A nasty trap to debug — beware.

13 supported languages: `c, cpp, csharp, go, java, javascript, kotlin, python3, ruby, scala, swift, mysql, oracle`

## 4. Message Sequence

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

The `identifier` must be **byte-for-byte identical** to the string sent at subscription
(ActionCable uses it as the key).

`{"type":"ping"}` is a heartbeat arriving every 3 seconds; ignore it.

Every response arrives wrapped as `{"identifier":"…","message":{…}}`.
`message.action` is the requested action; `message.type` is the specific kind.

## 5. Algorithm Problem Responses (measured)

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

**Wrong answers arrive in the same sequence**, with `passed:false` and the partial score in
`userScore`. Measured: 1 of 16 passed → `"userScore":"1.4"` (per-testcase weights are not uniform).

## 6. SQL Problem Responses (measured)

**Field naming differs from algorithm problems. The parser must handle both.**

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

### run (example execution — leaves no submission history)

```jsonc
{"action":"run","type":"start","testcase_ids":[5437],
 "challengeable_type":"database","challengeable_id":2778}

{"action":"run","type":"finish","testcase_id":5437,"passed":true,"msg":null,
 "returned_rows":"{\"columns\":[\"USERS\"],\"data\":[[4]]}",
 "challengeable_type":"database","challengeable_id":2778}
```

`returned_rows` is a **double-encoded JSON string**. It must be parsed once more to get the table.
`run` and `submit` use different testcases (example 5437 vs grading 5438).

### Differences from algorithm problems

| Item | Algorithm | SQL |
|---|---|---|
| Field naming | camelCase (`testcaseId`) | **snake_case** (`testcase_id`) |
| `test_group` message | present | **absent** (`start` carries `testcase_ids`) |
| Per-case time/memory | `run_time`, `memory_size` | **absent** |
| `scores` array | present | **absent** |
| Rating change | `isNewRating`, `old/newUserRating` | **absent — no rating impact** |
| `finish` message | present | **absent — ends at `result_lesson_challenge`** |
| Type/ID echo | in the result only | **in every message** |
| Testcase count | 16 (example) | 1 |

> **Caution**: SQL never sends `finish`. Detecting completion by waiting for `finish` hangs forever.
> Receiving `result_lesson_challenge` must be part of the termination condition.

## 7. Failure Type Classification (measured)

### submit path — distinguishable only by the `msg` string

The `msg` of the `testcase` message is the only clue. `exitCode` and `stderr` **never arrive.**
(Measured `msg` strings kept verbatim — `통과` = pass, `실패` = failure.)

| Type | `msg` | `run_time` / `memory_size` |
|---|---|---|
| Pass | `"통과 (0.01ms, 85.2MB)"` | present |
| Wrong answer | `"실패 (0.01ms, 75.3MB)"` | present |
| Runtime error | `"실패 (런타임 에러)"` | **`null`** |
| Compile error | `"실패 (런타임 에러)"` | **`null`** |
| Timeout | `"실패 (시간 초과)"` | **`null`** |

> **Compile errors and runtime errors cannot be distinguished from the submit response alone.**
> Both are reported as `"실패 (런타임 에러)"`. Use the `run` action to tell them apart.

In all three cases `result_lesson_challenge` arrives with `userScore: "0.0"`, `passed: false`,
and `finish` arrives normally.

### run path — gives the full error text

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

- `msg` is **HTML-escaped**. Newlines are `<br/>`, quotes are `&quot;` / `&#39;`.
  Displaying it requires unescaping plus replacing `<br/>` → `\n`.
- `index` indicates **which example blew up** (0-based).
- The `input` of an algorithm `run` is a comma-joined argument string like `"3, 2"`.

**The `testcases` carried in `run`'s `start` is the best path for local scaffolding.**
Example inputs/outputs arrive already structured — no need to parse the problem body HTML for tables.

## 8. Full type Catalog (extracted from the bundle)

```
submit : start, test_group, testcase, result_without_score, result,
         result_lesson_challenge, finish, notice, paused, error
run    : start, testcase, result, finish, notice
save   : result, failed_git, failed_save
reset  : completed, failed
```

Fields that can appear in `submit` responses:
`category, challengeableId, exitCode, finishCondition, isNewRating, msg,
newUserRating, oldUserRating, passed, passingScore, perfectScore, scores,
scoringUnit, stderr, testcaseId, testcaseIds, type, userScore, run_time, memory_size`

Algorithm `run` response fields (per the bundle):
`exitCode, hideResult, index, msg, passed, passedCount, stdout, stderr, testcases, totalCount, type`

In measurements, on error the stream ended with `type:"error"` + `msg` (full text), and `stdout`
did not come along. Whether `stdout` arrives on successful execution is unverified.

### Other actions

- `run` — runs example testcases only. Leaves no submission history. ✅ verified for both algorithm and SQL
- `save` — code autosave. The response carries `challengeableId`, usable for id validation. ✅ verified
- `stop` / `reset` / `finish` — abort execution / reset / end exam. Unverified

## 9. Problem List API

```
GET /api/v2/school/challenges/?perPage=100&page=1
GET /api/v2/school/challenges/?perPage=100&statuses[]=solved&page=1
```

**Works without authentication**; when logged in, `status` splits into `solved`/`unsolved`.

```jsonc
{"page":1,"perPage":100,"totalPages":7,"totalEntries":689,
 "languages":["c","cpp","csharp","go","java","javascript","kotlin",
              "python3","ruby","scala","swift","mysql","oracle"],
 "result":[{"id":468381,"title":"기차 선로","partTitle":"2025 카카오 하반기 2차",
            "level":3,"finishedCount":310,"acceptanceRate":4,
            "status":"unsolved","openedAt":"…","aiCommentable":false}]}
```

689 problems in total. `partTitle` is a type/source label (`해시` (hash), DFS/BFS, SELECT,
2024 KAKAO …), so it can serve as a weak-point analysis axis.

- Level distribution: `Lv0 240, Lv1 119, Lv2 155, Lv3 109, Lv4 45, Lv5 21`
- 106 SQL problems: `SELECT 33, GROUP BY 24, String·Date 19, JOIN 12, SUM·MAX·MIN 10, IS NULL 8`

## 10. Broadcast — Passive Observation (measured)

**Every client subscribed to the same `identifier` receives identical messages simultaneously.**
This is because ActionCable streams are scoped by channel parameters, not by connection.

In other words, **if the server merely keeps a subscription to the channel, grading results the
user triggers in the browser flow into the server as well.** No proxy, no browser-extension
traffic interception needed.

### Verification 1 — two sockets on the same page

Socket B only subscribes and waits; socket A fires `run`:

```
A_received: start@2050  testcase@3150  testcase@3150  result@3150
B_received: start@2050  testcase@3150  testcase@3150  result@3150
```

Identical messages, identical timestamps.

### Verification 2 — a separate process (the key one)

A Python process connects and subscribes with only the `_session_production` cookie,
and `run` is fired **from the browser**:

```
[ 0.40s] CONNECTED subprotocol=actioncable-v1-json
[ 0.43s] CONFIRM_SUBSCRIPTION       ← 브라우저 아닌 프로세스도 인증 통과
[12.98s] BROADCAST action=run type=start
[13.99s] BROADCAST action=run type=testcase
[14.07s] BROADCAST action=run type=testcase
[14.07s] BROADCAST action=run type=result
```

Exactly matches the 4 messages the browser received. **The passive-observation architecture holds.**

Headers required on connect:

```
Cookie: _session_production=…; tracking_id=…; locale=…; timezone=…
Origin: https://school.programmers.co.kr
Sec-WebSocket-Protocol: actioncable-v1-json
```

### Limitation — no wildcard subscription

The `identifier` must match character-for-character; there is no pattern subscription like
`challengeable_id: *`. There is also no way to subscribe to "all submissions of this user".
Therefore **the server must know in advance which problem the user has opened** in order to
subscribe to that channel.

With 689 problems × 13 languages, subscribing to everything is unrealistic.

### Result messages carry no code — retrieve it separately

Broadcasts contain no source code. Instead, **the problem page fetched while logged in contains
the user's last saved code** (autosaved by the `save` action while editing, and also saved
on `submit`).

```
GET /learn/courses/30/lessons/{lessonId}?language={lang}   (인증 필요)
  → <input data-type="code" value="<저장된 내 코드>">
  → <input id="initial_code_{id}" value="<문제 초기 골격>">   비교용
```

Measured:

```
59036  savedCode: "SELECT ANIMAL_ID, NAME FROM ANIMAL_INS WHERE INTAKE_CONDITION = 'Sick'…"
120804 savedCode: "class Solution { public int solution(int num1, int num2) { return num1 * num2; } }"
```

## 11. There Is No Submission History API

Past submissions cannot be queried. An exhaustive check of the API paths in the bundle found
only `/essay_submissions`, `/quiz_submissions`, and `/skill_checks/…/source.js` as
submission-related endpoints, all belonging to other products (essay · quiz · skill check).
None exists for coding test practice.

Only two things are queryable.

```
GET /api/v2/school/challenges/?statuses[]=solved      풀었다/못 풀었다
GET /api/v1/main/open-challenge-activities?year=2026  날짜별 시도 횟수 집계
```

The latter's response:

```jsonc
{"solvedChallengeCount":43,"attemptedChallengeCount":46,"attemptTotalCount":449,
 "attempts":[["2025-01-02",35],["2025-01-03",9], …]}   // 날짜와 횟수뿐
```

No problem, no code, no pass/fail. **A grading result not captured at that moment is lost forever.**

## 12. Problem Body · I/O Examples

`GET /learn/courses/30/lessons/{lessonId}?language=java` (no auth required)

- Body: `div.guide-section-description > div.markdown`
- I/O examples: `<table>` inside the body → `[['num1','num2','result'], ['2','3','-1'], …]`
- Per-language initial code: `<input data-type="code" data-language="java" value="…">`
- For SQL, the body contains the table schema (column names · types · Nullable) as a table

Everything needed for local scaffolding is available here.

## 13. Caveats (measurement-based)

1. **Grading requests are independent of the open page.** Submitting 120803 from the 12943 page
   works fine. Any problem can be graded over a single connection.

2. **Consecutive submissions to the same problem corrupt server state.** On resubmitting identical
   code, a cached result came back within 1 second and result finalization failed with `error`.
   Leave a gap between submissions.

3. **No concurrent submissions.** Opening overlapping WebSockets for the same problem raises
   `error`. The original UI also prevents duplicate runs with `channel.isIdle()`.

4. `enabledDailyLimit` / `dailyLimitType` / `dailyLimitProgress` fields exist.
   They appear not to apply to practice problems, but Dev-Course assignments may have daily limits.

5. **Measured grading durations — important for client timeout design:**

   | Situation | Duration |
   |---|---|
   | Algorithm normal grading (Java, 16 cases) | 6~9 s |
   | Algorithm runtime/compile error | 4~7 s |
   | **Algorithm timeout (16 cases)** | **~87 s** |
   | SQL grading | 1~2 s |
   | `run` (example execution) | 1~2 s |

   The timeout case is overwhelmingly the slowest. The client timeout must be **at least
   120 seconds** so a legitimate timeout verdict is not cut off midway.

## 14. Unverified Items

- The 2-entry `scores` array shape for problems with efficiency tests
- Whether `stdout` is retrievable on a successful algorithm `run`
- `Challenge::SqlChannel` (unused — practice problems are all `database` type)
- Exact rate-limit rules
- Oracle submissions
- Memory-limit-exceeded (`메모리 초과`) message — never triggered

## 15. Verification Log

| # | Problem | Type | Action | Result |
|---|---|---|---|---|
| 1 | 120803 | Algorithm | submit | Wrong answer → `result_lesson_challenge` received, score 1.4 (id 49587 — wrong value) |
| 2~5 | 120803 | Algorithm | submit | Correct → 16/16 passed but result finalization `error` (id confusion + repeated submission) |
| 6 | **120804** | Algorithm | submit | **Score 100, rating 1371→1372, `finish` normal** (id 14643) |
| 7 | **131528** | **SQL** | submit | **Score 100, ended without `finish`** (id 2778) |
| 8 | 131528 | SQL | `run` | `returned_rows` received, no submission history left |
| 9 | 120820 | Algorithm | submit | Compile error → all 12 cases `"실패 (런타임 에러)"`, score 0 |
| 10 | 120810 | Algorithm | submit | Runtime exception → all 14 cases `"실패 (런타임 에러)"`, score 0 |
| 11 | 120805 | Algorithm | submit | Infinite loop → all 16 cases `"실패 (시간 초과)"`, score 0, **took 87 s** |
| 12 | 120820 | Algorithm | `run` | **Full compiler output retrieved** (`/Solution.java:3: error: ';' expected`) |
| 13 | 120810 | Algorithm | `run` | **Full stack trace retrieved** (`ArrayIndexOutOfBoundsException`) |
| 14 | **120804** | Algorithm | `run` | **Success path captured frame by frame from our own Kotlin client** — `start` → `testcase` ×2 → **`result`** (`passedCount` 2 / `totalCount` 2). Terminal is `result`, **not** `finish`; cases identify themselves by 0-based **`index`**, not `testcaseId`; **index 1 arrived before index 0**. Captured 2026-08-04 (issue #6), reproduced byte-alike 2026-08-05 after the Spring Boot 4 upgrade (#10) |

Server-side effect confirmed by the solved count rising 90 → 92. Rating 1371 → 1372.
Entries 9~11 were intentional failing submissions, so those problems remain unsolved.

Entry 14 upgrades the algorithm-`run` terminal and the run testcase shape from
bundle-derived (§8) to measured. The frames are kept verbatim as
`src/test/resources/fixtures/algorithm-run-pass.jsonl`.

### 15.1 Does `run` save the code? — measured 2026-08-05 (issue #20)

Yes. The saved code on the problem page changed only after `run` was pressed.

| Step | Saved-code SHA-256 | Size |
|---|---|---|
| Baseline | `f7a5375…` | 98 chars, 5 lines |
| After editing in the browser, **before** `run` | `f7a5375…` — unchanged | 98 chars, 5 lines |
| After pressing `run` | `22c9725…` | 123 chars, 6 lines |

Method: `GET /learn/courses/30/lessons/120804?language=java` while authenticated, reading
`<input data-type="code">` and hashing it (`./gradlew liveCodeFetch`). Lesson 120804,
algorithm, Java.

**Confirming trial (same session): no time-based autosave.** The code was edited again and
then left alone, with `run` never pressed:

| Step | Saved-code SHA-256 |
|---|---|
| Immediately after the second edit | `22c9725…` — the value `run` had saved |
| **After 3 minutes with no `run`** | `22c9725…` — still unchanged |

This eliminates the debounce hypothesis rather than merely making it unlikely: a debounce
short enough to explain the first trial's save would also have fired during these three
idle minutes, and none did. `run` is therefore the cause.

Still unmeasured: SQL problems, and other languages. The mechanism is very unlikely to
differ, but it has not been observed.
