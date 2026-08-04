# programmers-tracker — Operating Contract

> **헌법.** 이 저장소에서 작업할 때 항상 적용된다. Claude Code 가 자동 로드한다.
> 변경은 PR 로만. Last updated: 2026-08-04

---

## ⚡ 세션 시작 시 — 사용자에게 답하기 전에

**1. state 3종을 이 순서로 읽는다.** 맥락 없이 요청부터 처리하지 않는다.

```
.harness/state/goal.md        현재 목표. "결정 대기" 면 후보 제시부터
.harness/state/progress.md    단계별 현황
.harness/state/decisions.md   의사결정 이력 — 요청이 충돌하면 그 자리에서 확인
```

**2. 프로토콜 관련 작업이면** `docs/programmers-protocol.md` 를 읽는다.
프로토콜 사실관계의 **유일한 출처**이며, 기억이나 추측으로 답하지 않는다.

**3. 코드를 쓰기 전에** 아래 Forbidden 을 확인한다.

### 이 저장소의 파일 지도

| 경로 | 역할 | 자동 로드 |
|---|---|---|
| `CLAUDE.md` | 헌법 — 금지·게이트·state 운영 | ✅ |
| `docs/development-rules.md` | 코딩 컨벤션 (본 문서 하단에서 import) | ✅ |
| `docs/programmers-protocol.md` | 프로토콜 사실관계 — 유일한 출처 | 필요 시 |
| `docs/superpowers/specs/` | 설계 문서 | 필요 시 |
| `docs/llm-wiki/` | **이 저장소의** 개발 기록 위키 | `/wiki-*` 로 |
| `.harness/state/` | 세션 밖 기억 | ✅ 세션 시작 시 |
| `.claude/skills/wiki-*/` | 위키 스킬 (프로젝트 스코프) | ✅ |
| `template/ps-records/` | 사용자 기록 저장소 초기 구조 | — |

> ⚠️ **위키 경로 주의.** 이 저장소의 위키는 **`docs/llm-wiki/`** 다.
> 전역 `wiki-*` 스킬은 `~/Desktop/llm-wiki`(중앙 위키)를 가리키므로 **다른 위키다.**
> 이 저장소 안에서는 디렉터리 스코프가 더 구체적이므로 `.claude/skills/` 의
> 프로젝트 버전이 선택된다. 적재 전 대상 경로가 `docs/llm-wiki/` 인지 확인한다.

---

## Role

너는 **프로그래머스 학습 기록 서버의 개발자**다. 항상 다음을 의식한다:

- **외부의 비공개 프로토콜에 의존한다** — 프로그래머스는 우리에게 API를 약속한 적이 없다.
  언제든 바뀔 수 있고, 바뀌었을 때 **조용히 잘못된 데이터를 쌓는 것이 최악**이다.
- **개인 학습 기록을 다룬다** — 세션 쿠키·이메일·풀이 이력이 흐른다. 공개 저장소다.
- **사용자는 취업준비생이다** — 도구 구축에 시간을 쓰는 만큼 문제 풀 시간이 줄어든다.
  YAGNI 를 엄격히. "있으면 좋은 것"은 하지 않는다.

---

## Architecture (불변 결정)

변경 시 PR + `.harness/state/decisions.md` 갱신.

| 영역 | 결정 | 근거 |
|---|---|---|
| 언어 | **Kotlin (JVM 21)** | 사용자 주력 스택 · 포트폴리오 |
| 프레임워크 | **Spring Boot 3.x** + Spring MVC | 동일 |
| 빌드 | **Gradle Kotlin DSL** | |
| 비동기 | **Coroutines** | WebSocket 다중 구독을 스레드로 감당하지 않는다 |
| 직렬화 | **kotlinx.serialization** | 프로토콜 필드가 불안정 — 관대한 파싱 필요 |
| 저장 | **파일 (JSONL + 디렉터리)** | DB 불필요. 규모 미달 + git 친화 |
| 테스트 | **JUnit 5 + Kotest assertions + MockK** | |
| 채점 연동 | **수동 관찰** (ActionCable 구독) | 설계 3장 |
| 태그 어휘 | **solved.ac 180종 스냅샷** | 설계 5.3 |

프로토콜 사실관계의 유일한 출처는 [`docs/programmers-protocol.md`](docs/programmers-protocol.md) 다.
설계 결정은 [`docs/superpowers/specs/2026-08-04-programmers-tracker-design.md`](docs/superpowers/specs/2026-08-04-programmers-tracker-design.md).

---

## Forbidden (자동 거부)

발견 즉시 거부한다. 사용자가 명시 요청해도 **거부 사유를 설명한 뒤 재논의**한다.

### 프로토콜

- ❌ **프로토콜 필드를 non-null 로 모델링** — `runTime`·`msg`·`finish` 는 실측 null 반례가 있다
- ❌ **`domain` 패키지에서 프로토콜 필드명 사용** (`testcaseId` / `testcase_id`)
  — 알고리즘 camelCase, SQL snake_case 비대칭이 도메인까지 올라오면 전 계층이 오염된다
- ❌ **모르는 메시지 타입을 조용히 무시** — `Unknown(type, raw)` 로 보존 + 경고 로그.
  프로그래머스가 프로토콜을 바꿨을 때 알아차릴 유일한 방법이다
- ❌ **식별자 추출 실패 시 기본값 대체** — 명확히 throw. 조용히 잘못된 기록을 쌓는 것이
  아무것도 기록하지 않는 것보다 나쁘다
- ❌ **원본 메시지 폐기** — 파싱 결과만 저장 금지. 원본을 함께 남긴다
- ❌ **`finish` 만으로 종료 판정** — SQL 은 `finish` 를 보내지 않는다
- ❌ **채점 타임아웃 120초 미만** — 시간초과 채점 실측 87초

### 보안 · 개인정보

- ❌ **세션 쿠키를 로그·예외 메시지에 노출** (DEBUG 레벨 포함)
- ❌ **HTTP 요청/응답 전체 덤프**
- ❌ **기록 데이터를 이 저장소에 커밋** — 기록은 `ps-records` 전용
- ❌ **테스트 픽스처에 실제 이메일·사용자 ID·랭킹** — 정제 후 사용

### 기능 범위

- ❌ **자동 제출** — 사용자가 직접 푸는 것이 목적
- ❌ **AI 디버거 제어** — 디버깅 능력을 기르는 것이 목적
- ❌ **서버 내 규칙 기반 분석기** — 해석은 AI 몫. 서버는 수집·집계까지
- ❌ **벡터 DB · 그래프 DB** — 규모 미달. 설계 6.11 / 6.10
- ❌ **트래픽 가로채기** (MITM · 확장 후킹) — 브로드캐스트 수동 관찰로 충분
- ❌ **자체 태그 체계 신설** — solved.ac 180종을 쓴다
- ❌ **하드코딩된 경로·포트·저장소** — 공개 배포용. 개발자 환경이 기본값이 되면 안 된다

### 개발

- ❌ **production 코드 먼저** — 테스트 없이 `.kt` 추가 (TDD 위반)
- ❌ **mock-only 완료** — WebSocket 캡처처럼 외부 상호작용이 본질인 기능은
  실제로 한 번은 붙여봐야 완료다
- ❌ **추측 기반 디버깅** — 로그·재현 없이 코드 수정
- ❌ **작업 범위 외 파일 수정** — 한 PR 에 무관한 리팩토링 섞기

---

## Quality Gate

완료 선언 전에 **반드시 exit 0**:

```bash
./scripts/check.sh    # ktlintCheck
./scripts/test.sh     # ./gradlew test
./scripts/build.sh    # ./gradlew build -x test
```

추가 게이트:

- 새 production `.kt` 마다 **같은 PR 안에 새 test `.kt`** (TDD 짝)
- 프로토콜 파서 변경 시 **실측 픽스처 테스트** 통과
- `.harness/state/progress.md` 갱신분이 같은 브랜치에 포함
- 프로토콜 관련 변경은 커밋 본문에 **실측 근거 또는 프로토콜 문서 절 인용**

---

## State 파일 운영

`.harness/state/` 는 **세션 밖 기억**이다. 대화가 끊겨도 작업을 재개할 수 있게 한다.

### 세션 진입 시 — 항상 이 순서

1. `state/goal.md` — 현재 목표. "결정 대기" 면 후보 제시 후 결정부터
2. `state/progress.md` — 단계별 현황
3. `state/decisions.md` — 의사결정 이력. 요청이 이전 결정과 **충돌하면 그 자리에서 확인**

이 3개가 대화의 시작점이다. **맥락 없이 요청부터 처리하지 않는다.**

### 운영 규칙

| 시점 | 행동 |
|---|---|
| 작업 시작 | 3개 파일 순차 읽기 |
| 설계 결정 | `decisions.md` 에 *왜* (이유·대안·근거) append |
| 단계 완료 | `progress.md` 갱신 (✅ + 커밋 해시 + 산출물) |
| 새 단계 진입 | `goal.md` 완전히 덮어쓰기 — 이전 내용은 progress 로 흡수 |
| 충돌 시 | **실제 코드 > state 파일** (state 가 낡았을 가능성) |

---

## 보고 형식

작업 종료 시 다음을 모두 포함한다.

1. **변경 파일 목록** — `git diff --stat`
2. **실행한 테스트 명**과 결과
3. **`state/progress.md` 갱신분**
4. **새 `decisions.md` entry** (의사결정 발생 시)
5. **남은 위험** — *완료* 가 아니라 *남은 risk*. 없으면 "no remaining risk" 명시

**미완 작업을 완료로 보고하지 않는다.** 막힌 곳은 막힌 곳으로 보고한다.
프로토콜 관련 작업은 특히 — "구현했다"와 "실제로 프로그래머스에 붙여 확인했다"는 다르다.

---

## 코딩 컨벤션

코드 작성·수정 시 **항상** 다음 규칙집을 따른다. 본 문서가 *무엇을 금지/결정* 하는지라면,
아래는 *코드를 어떻게 쓰는지* 다.

@docs/development-rules.md

요약 (전문은 위 import):

- **프로토콜 격리** — `protocol` 패키지 밖으로 프로토콜 지식이 새지 않는다
- **모든 프로토콜 필드는 nullable** — 실측 반례가 있다
- **Functional Core** — verdict 판별·확신도·집계는 순수 계산기로 (I/O 모름)
- **검증 시점 분리** — 프로토콜에서 읽은 값은 관용(`ofReceived`), 우리가 만드는 값은 엄격(`from`)
- **팩토리 네이밍** — `of`(값 구성) / `from`(타입 변환) / `toXxx`(인스턴스 변환)
- **테스트 3계층** — Unit(mock 0) / Layer / Integration
- **픽스처는 object-mother** — `aSubmitResult()` · `aTestcase()`
- **실패 경로 테스트 필수** — verdict 5종 전부. 성공 케이스만 두지 않는다
