# programmers-tracker — 코딩 컨벤션

> **헌법 부속 문서.** [`CLAUDE.md`](../CLAUDE.md) 가 *무엇을 금지/결정* 하는지라면,
> 본 문서는 *코드를 어떻게 쓰는지* 의 규칙집이다. 새 `.kt` 작성·수정 시 **항상** 따른다.
>
> Last updated: 2026-08-04

---

## 핵심 5원칙 (전역)

`~/.claude/CLAUDE.md` 팀 표준을 승계한다.

1. 한 메서드 = 한 일, 최대 10줄
2. `else` 금지, early return
3. primitive·collection 은 도메인 객체로 (→ §4 VO)
4. getter 보다 행위 메서드
5. 상속보다 합성

Kotlin 파일은 Effective Kotlin 원칙을 적용한다 — `val` 우선, 널 안전성, `data class`,
범위 함수, `sealed class`.

---

## 1. 패키지 구조 — 위치가 역할을 말한다

```
com.brokenfinger.tracker/
  protocol/         ← 프로그래머스 프로토콜을 아는 유일한 곳
    ActionCableClient.kt
    ChannelIdentifier.kt
    message/          수신 메시지 DTO (프로토콜 필드명 그대로)
    parse/            DTO → 도메인 변환
  domain/           ← 프로토콜을 모른다
    Submission.kt  Verdict.kt  Attempt.kt  Tag.kt
    calc/             순수 계산기 (§3 Functional Core)
  application/      ← 응용 서비스 · Result DTO
  adapter/
    web/              HTTP 컨트롤러 (센서 확장용 /watch)
    mcp/              MCP 도구 · 리소스
    store/            파일 저장 (JSONL · 디렉터리)
    git/              GitSync
```

클래스 접미사는 Spring 관례를 유지한다 — `XxxController` · `XxxService` · `XxxRepository`.

의존 방향은 `adapter → application → domain` 이며 `protocol → domain` 은 `parse` 에서만 일어난다.
**`domain` 은 어떤 것도 import 하지 않는다.**

---

## 2. 프로토콜 의존 격리 — 최우선 규칙

### 2.1 한 곳에 가둔다

`domain` 의 어떤 클래스도 `testcaseId` / `testcase_id` 같은 프로토콜 필드명을 알아서는 안 된다.
프로토콜이 바뀌면 `protocol/parse` 만 고치면 되도록 유지한다.

**근거**: 알고리즘 문제는 camelCase(`testcaseId`), SQL 문제는 snake_case(`testcase_id`)를 쓴다.
이 비대칭을 도메인까지 끌고 올라가면 모든 계층이 오염된다.

### 2.2 모든 프로토콜 필드는 nullable 이다

실측으로 확인된 사실이다. 낙관적으로 모델링하면 런타임에 터진다.

```kotlin
// ❌ 실측 반례가 있다
data class TestcaseMessage(val runTime: String, val memorySize: Long)

// ✅
data class TestcaseMessage(
    val testcaseId: Long?,      // SQL 은 testcase_id 로 온다
    val passed: Boolean?,
    val msg: String?,           // SQL run 응답에서 null 관측
    val runTime: String?,       // 런타임에러·시간초과 시 null
    val memorySize: Long?,
)
```

| 필드 | null 이 되는 조건 |
|---|---|
| `runTime`, `memorySize` | 런타임 에러 · 컴파일 에러 · 시간 초과 |
| `msg` | SQL `run` 응답 |
| `finish` 메시지 자체 | **SQL 은 보내지 않는다** |
| `scores`, `isNewRating` | SQL 전체 |

### 2.3 모르는 것은 버리지 않고 남긴다

```kotlin
sealed interface SubmitMessage {
    data class Testcase(...) : SubmitMessage
    data class Result(...) : SubmitMessage
    data class Unknown(val type: String, val raw: JsonObject) : SubmitMessage  // 필수
}
```

`Unknown` 은 경고 로그를 남기고 원본 JSON 그대로 레코드에 보존한다.

### 2.4 원본을 항상 보존한다

파싱 결과만 저장하면 나중에 재해석할 수 없다. 수신 메시지 원본을 함께 남긴다
(`attempts/00N.raw.jsonl`). 디스크는 싸고, 사라진 데이터는 복구할 수 없다.

---

## 3. Functional Core — 판정·집계는 순수 계산기로

**불변식·판정·집계 로직은 I/O 를 모르는 순수 클래스**로 작성한다.
입력 = 메모리 스냅샷(파라미터), 출력 = 판정 결과. 파일·네트워크·git 참조 금지.

대상:

| 계산기 | 입력 | 출력 |
|---|---|---|
| `VerdictResolver` | 제출 메시지 + 직전 run 레코드 | `Verdict` 5종 |
| `ConfidenceCalculator` | 시도 횟수·힌트 단계·소요시간 | 확신도 → 다음 복습일 |
| `PerformanceScorer` | acceptanceRate · level · 실제 시도 | 기대 대비 성과 |
| `StuckTestcaseFinder` | 제출 이력 | 끝까지 실패한 케이스 번호 |
| `SlowPassDetector` | runTime + 동일 태그·레벨 분포 | 느린 통과 여부 |

**서비스는 조립·저장만 한다.** 데이터를 모아 계산기에 넘기고, 결과로 저장/커밋을 실행한다.
판정 도중 파일을 읽지 않는다 — 스냅샷 선로드가 강제된다.

효과:
- mock 0 단위 테스트 — 경계값 전수 검증 가능
- 같은 계산기를 실시간 캡처와 과거 데이터 재분석이 공유 → 드리프트 원천 차단

❌ 반례: 서비스 안 인라인 판정. 단위 테스트 불가, 재분석 경로에서 재사용 불가.

---

## 4. 값 객체 · 검증 시점 분리

의미 있는 값은 raw String 금지 → VO. `data class`, 생성 시 검증, 행위 메서드 보유.

```kotlin
@JvmInline value class LessonId(val value: Long)
@JvmInline value class ChallengeableId(val value: Long)
@JvmInline value class CodesKey(val value: String)
```

`ChallengeableId` 와 `CodesKey` 는 **반드시 다른 타입**이어야 한다. 둘 다 Long/String 이면
바꿔 넣어도 컴파일이 통과하는데, 그 혼동이 실제로 리버스 엔지니어링 단계에서
반복 실패를 일으켰다 (프로토콜 문서 3장).

### 검증 시점 분리 (receive-first)

**입력 경계는 엄격, 외부에서 받은 값은 관용적으로** 다룬다.

```kotlin
// 우리가 만드는 값 — 엄격. 위반 시 throw
fun from(raw: String): Tag

// 프로토콜·저장소에서 읽은 값 — 관용. 절대 throw 하지 않는다
fun ofReceived(raw: String?): Tag?
```

**근거**: 프로그래머스가 새 값을 보내기 시작했을 때 파싱이 throw 하면 그 제출 기록이
통째로 유실된다. 기록 유실이 검증 실패보다 훨씬 큰 손해다. 관용적으로 받아
`Unknown` 으로 남기고 경고한다.

---

## 5. 정적 팩토리 네이밍

| 접두 | 의미 | 예 |
|---|---|---|
| `of` | 값·구성요소로부터 생성 | `ChannelIdentifier.of(lessonId, challengeableId, lang)` |
| `from` | **타입 변환** (다른 타입 → 이 타입) | `Submission.from(message)` · `SubmissionRecord.from(submission)` |
| `ofReceived` | 외부 수신값에서 관용 생성 (throw 금지) | `Verdict.ofReceived(msg)` |
| `toXxx` | 이 객체 → 다른 타입 (인스턴스 메서드) | `submission.toRecord()` |

생성자 직접 호출보다 팩토리를 우선한다.

---

## 6. 테스트

### 6.1 3계층 분할

| 계층 | 대상 | 인프라 | 위치 |
|---|---|---|---|
| **Unit** | 도메인 모델 · 순수 계산기 | **mock 0개** | `test/domain/**` |
| **Layer** | 파서 · 서비스 · 컨트롤러 | 픽스처 / MockK | `test/protocol/**`, `test/application/**` |
| **Integration** | 실제 프로그래머스 접속 | 세션 쿠키 | `test/integration/**` |

### 6.2 실측 메시지를 픽스처로 고정한다

프로토콜 파서는 **실제로 캡처한 메시지**로 테스트한다. 손으로 지어낸 JSON 은
우리가 상상한 프로토콜을 검증할 뿐이다.

```
src/test/resources/fixtures/
  algorithm-pass.jsonl        120804 · 16/16 · 레이팅 1371→1372
  algorithm-wrong.jsonl       120803 · 1/16 · 부분점수 1.4
  algorithm-timeout.jsonl     120805 · 시간 초과 · 87초
  algorithm-runtime.jsonl     120810 · 런타임 에러
  algorithm-compile.jsonl     120820 · 컴파일 에러
  sql-pass.jsonl              131528 · snake_case · finish 없음
  sql-run.jsonl               131528 · returned_rows 이중 인코딩
```

각 픽스처는 [프로토콜 문서](programmers-protocol.md) 15장 검증 로그의 실제 캡처다.

### 6.3 실패 경로 테스트 필수

**verdict 5종을 전부 커버**한다. 성공 케이스만 두지 않는다.
`Unknown` 메시지 · 식별자 추출 실패 · 쿠키 만료 · 구독 거부도 테스트한다.

### 6.4 픽스처는 object-mother

테스트 객체는 빌더 함수로 만든다. 인라인 생성자 반복 금지.

```kotlin
// support/fixtures/SubmissionFixtures.kt
fun aSubmission(verdict: Verdict = Verdict.PASS, attempt: Int = 1) = ...
fun aTestcase(passed: Boolean = true, runTime: String? = "0.01") = ...

// 사용 — 변경 필드만 named-param override
val timeout = aSubmission(verdict = Verdict.TIMEOUT)
```

새 도메인 타입 도입 시 `*Fixtures.kt` 에 `aXxx()` 빌더부터 추가하고 그것으로 테스트를 쓴다.

### 6.5 통합 테스트

- 기본 비활성 (`@Tag("integration")`), 로컬에서 명시적으로만 실행
- 세션 쿠키가 없으면 **skip — 실패가 아니다**
- CI 에서는 돌리지 않는다
- Lv0 문제 대상으로만 (계정 기록에 영향 최소화)

---

## 7. 개인 데이터 취급

### 7.1 절대 커밋하지 않는 것

```gitignore
.harness/state/goal.md      # 개인 작업 상태
.ps/session
.ps/cookies*
*.local.yml
application-local.yml
```

기록은 별도 저장소 `ps-records` 에 남긴다. 이 저장소에는 어떤 풀이 기록도 들어가지 않는다.

### 7.2 자격증명 마스킹

세션 쿠키는 메모리에만 둔다. `SessionProvider` 하나로 다루는 코드를 제한하고,
밖으로는 값 클래스만 넘긴다.

```kotlin
@JvmInline
value class SessionCookie(private val raw: String) {
    fun headerValue(): String = raw
    override fun toString(): String = "SessionCookie(***)"
}
```

### 7.3 테스트 픽스처 정제

실측 메시지를 픽스처로 쓰되 이메일·사용자 ID·랭킹은 치환한다.
`surveyUrl`·`finishModalLink` 같은 개인 식별 가능 경로도 마찬가지다.

---

## 8. 외부 의존은 스냅샷으로 고정

우리가 통제하지 못하는 데이터는 로컬에 복제한 뒤 그것만 읽는다.

| 대상 | 스냅샷 | 갱신 |
|---|---|---|
| solved.ac 태그 어휘 180종 | `.ps/tag-vocab.json` | 수동 |
| 프로그래머스 문제 카탈로그 689개 | `.ps/catalog.json` | 하루 1회 |

**근거**: 백준 온라인 저지가 2026년 5월 종료했다. solved.ac API 는 아직 살아 있으나
언제까지일지 알 수 없다.

**외부 서비스가 전부 사라져도 핵심 기능(캡처·기록·분석)은 동작해야 한다.**

---

## 9. 공개 저장소로서의 규칙

### 9.1 하드코딩 금지

```yaml
tracker:
  record-repo: ${TRACKER_RECORD_REPO:~/ps-records}
  port: ${TRACKER_PORT:8080}
  language: ${TRACKER_LANGUAGE:java}
  browser: ${TRACKER_BROWSER:chrome}
```

### 9.2 플랫폼 종속은 인터페이스 뒤로

```kotlin
interface SessionProvider { fun cookie(): SessionCookie }

class MacChromeSessionProvider : SessionProvider   // Keychain 복호화
class ManualFileSessionProvider : SessionProvider  // 폴백 — 모든 플랫폼
```

**수동 파일 폴백은 선택이 아니라 필수다.** 자동 추출이 안 되는 환경에서도
프로젝트가 쓸모없어지지 않아야 한다.

### 9.3 프로그래머스에 대한 예의

README 에 명시한다.

- **개인 학습 기록용**이며 본인 계정에만 사용한다
- **자동 제출 기능을 제공하지 않는다** — 제출은 사용자가 브라우저에서 직접 하고,
  서버는 결과를 관찰해 기록할 뿐이다
- 서버가 보내는 요청은 채널 구독·문제 페이지 조회·카탈로그 조회뿐이며
  모두 브라우저가 하는 것과 같은 수준이다
- 카탈로그 폴링은 하루 1회를 넘기지 않는다
- 프로그래머스가 중단을 요청하면 따른다

> 비공개 프로토콜을 이용하는 도구를 공개 배포하는 것은 개인이 자기 계정에 쓰는 것과
> 성격이 다르다. 위 원칙을 지키면 실질적 문제는 없다고 보지만, 최종 판단과 책임은
> 배포자에게 있다. 라이선스에 면책 조항을 포함한다.

---

## 10. 스타일

- `.editorconfig` 의 ktlint 규칙을 **작성 시점에** 맞춘다 (사후 `ktlintFormat` churn 최소화)
- import ordering(알파벳), chain-method-continuation, function-signature wrapping, max-line-length
- 완료 전 `./scripts/check.sh` exit 0 필수

---

## 11. 커밋 · 브랜치

- 브랜치: `feat/` · `fix/` · `docs/` · `refactor/` · `test/`
- 커밋 메시지는 영어 Conventional Commits
- **프로토콜 관련 변경은 반드시 근거를 남긴다.** 실측 결과나 프로토콜 문서 절을 인용한다.
  6개월 뒤 "왜 이렇게 했지"에 답할 수 있어야 한다.

```
fix(protocol): treat result_lesson_challenge as terminal for SQL

SQL problems never send a `finish` message; the stream ends at
result_lesson_challenge. Waiting for `finish` hangs forever.

Verified 2026-08-04 on lesson 131528. See docs/programmers-protocol.md §6.
```

---

## 12. 문서

- `README.md` — 무엇을 해결하는가, 설치, 5분 안에 첫 기록 남기기
- `CLAUDE.md` — 헌법 (금지·게이트·state 운영)
- `docs/programmers-protocol.md` — 프로토콜 리버스 엔지니어링 결과
- `docs/development-rules.md` — 이 문서
- `docs/superpowers/specs/` — 설계 문서
- `LICENSE` — MIT

한국어로 쓴다. 대상 사용자가 한국 취업준비생이다.
