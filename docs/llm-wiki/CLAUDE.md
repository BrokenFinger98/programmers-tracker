# programmers-tracker Wiki — Schema (위키의 헌법)

이 디렉토리(`docs/llm-wiki/`)는 Karpathy **LLM Wiki** 패턴을 따르는 이 프로젝트의 지식 베이스다.
개인 PC가 아니라 **레포에 커밋**되므로, 클론한 사람은 같은 지식과 같은 `/wiki-*` 워크플로우를 쓴다.

> ⚠️ 경로는 **레포 루트 기준 상대경로**(`docs/llm-wiki/...`)만 쓴다.
> 개인 절대경로(`/Users/...`) 금지 — 다른 사람 PC에서 깨진다.

---

## 0. 이 위키의 이중 목적

일반적인 팀 위키와 **한 가지가 다르다.**

1. **개발 기억** — 세션이 끊겨도 "왜 이렇게 했는지"를 잃지 않는다
2. **포트폴리오 산출물** — 이 프로젝트는 이직용 포트폴리오를 겸한다.
   위키는 코드가 보여주지 못하는 **판단 과정**을 보여주는 자료다

따라서 결정 페이지는 **그 자리에 없었던 사람이 읽어도 이해되게** 쓴다.
"A로 정함"이 아니라 "B·C를 검토했고 ~ 근거로 A를 골랐다. 대신 ~ 비용을 받아들였다."

무엇을 **하지 않기로 했는지와 그 이유**가 특히 가치 있다. 기술 면접에서
"왜 그걸 안 썼나요"는 "왜 그걸 썼나요"보다 자주 나온다.

---

## 1. 3계층 구조

| 계층 | 경로 | 소유자 | 규칙 |
|---|---|---|---|
| Raw Sources | `docs/llm-wiki/raw/` | 사람이 큐레이션 | **불변**. 수정·삭제 금지. 진실의 원천 |
| Wiki | `docs/llm-wiki/wiki/` | **LLM 이 작성·갱신·교차링크** | 사람은 본문을 직접 쓰지 않는다 |
| Schema | `docs/llm-wiki/CLAUDE.md` | 사람이 설정 | 규칙 변경은 본문이 아니라 이 파일 |

```
docs/llm-wiki/
├── CLAUDE.md          # 이 파일 (스키마)
├── index.md           # 전체 카탈로그 — 검색 시 가장 먼저 읽는다
├── log.md             # ingest/query/lint 이력 (append-only)
├── README.md          # 사용법
├── raw/sessions/      # 적재 소스 (YYYY-MM-DD-제목.md)
└── wiki/
    ├── sources/       # 소스 1개당 요약 stub 1개 (추적성 앵커)
    ├── decisions/     # 중요 결정 (ADR: 맥락/결정/이유/대안/결과)
    ├── entities/      # 외부 시스템·라이브러리·API 등 "명사"
    ├── concepts/      # 개념·원리·노하우·디버깅 패턴
    └── syntheses/     # 3개 이상 소스를 종합한 페이지
```

이 프로젝트에서 각 분류가 담을 것:

| 분류 | 이 프로젝트에서의 내용 |
|---|---|
| `entities/` | 프로그래머스 ActionCable · solved.ac API · BaekjoonHub · MCP |
| `concepts/` | 브로드캐스트 수동 관찰 · verdict 판별 · Functional Core · 프로토콜 격리 |
| `decisions/` | 수동 관찰 채택 · Kotlin 선택 · 벡터 DB 기각 · 태그 어휘 외주 |
| `syntheses/` | 프로토콜 리버스 엔지니어링 전말 · 약점 분석 방법론 |

---

## 2. 페이지 작성 규칙

- 파일명 **kebab-case** + `.md`. 한 페이지 = 한 주제
- 모든 페이지 상단 frontmatter:

```yaml
---
type: source | entity | concept | decision | synthesis
project: programmers-tracker
tags: [주제태그, ...]
created: YYYY-MM-DD
updated: YYYY-MM-DD
sources: [raw/sessions/2026-08-04-foo.md]
---
```

- `type` = 형식 분류, `tags` = 주제 분류 (직교하는 검색축)
- `decisions/` 는 **결정 1건 = 1파일** `<YYYY-MM-DD>-<slug>.md`, ADR 형식.
  frontmatter 에 **`author`** 필수. 결정 번복 시 옛 결정을 지우지 말고
  새 파일 + 옛 파일에 `⚠️ superseded by ...`
- 모든 핵심 주장에 출처: `(raw/sessions/2026-08-04-foo.md)`

### 결정 페이지 형식 (ADR)

```markdown
## 맥락
무엇을 정해야 했는가. 제약은 무엇이었나.

## 검토한 선택지
A · B · C 각각의 장단점. **실제로 검토한 것만** 적는다.

## 결정
무엇을 골랐는가.

## 이유
왜. **측정·실측 근거가 있으면 반드시 인용한다.**

## 받아들인 비용
이 선택으로 포기한 것. 없으면 "없음"이라 쓰지 말고 다시 생각한다.

## 결과
실제로 어떻게 됐는가. (나중에 갱신)
```

**실측 근거를 특히 중시한다.** 이 프로젝트는 비공개 프로토콜을 다루므로
"그럴 것이다"와 "확인했다"의 차이가 결정적이다. 추측이면 추측이라고 명시한다.

---

## 3. 교차링크

- 다른 페이지 언급 시 `[[concepts/foo]]` · `[[entities/bar]]`
- 새 페이지는 **반드시** `index.md` 등록 + 최소 1개 인바운드 링크 (고아 금지)
- 과링크 금지 — 같은 대상은 한 페이지에 1회

---

## 4. 운영 원칙

1. **사람은 큐레이션, LLM 은 집필.** 본문은 `/wiki-ingest` · `/wiki-lint` 로만 갱신
2. **가려 넣는다.** "나중에 다시 볼 가치가 있나?"가 기준. 잡담·일회성 제외
3. **레포와 함께 버전관리.** ingest/lint 마다 커밋 — 위키도 1급 산출물
4. **index.md 를 신뢰.** 검색 출발점이므로 항상 정확히 유지
5. **append 항목은 `YYYY-MM-DD` 로 시작.** 충돌 시 시간순 union 으로 결정론적 해소.
   `log.md` 는 `.gitattributes` 에 `merge=union`

> 기여자가 생기기 전까지 `wiki-merge` 스킬은 두지 않는다 (YAGNI).
> fork 기반 사용에서는 동시 쓰기 충돌이 발생하지 않는다.

---

## 5. 이 프로젝트 고유 규칙

### 5.1 프로토콜 사실은 위키가 아니라 프로토콜 문서에

`docs/programmers-protocol.md` 가 프로토콜 사실관계의 **유일한 출처**다.
위키는 그것을 **참조**하되 복제하지 않는다. 복제하면 반드시 어긋난다.

위키에 쓸 것은 *사실* 이 아니라 **그 사실을 어떻게 알아냈고 무엇을 결정했는가** 다.

```
❌ wiki/concepts/actioncable.md 에 메시지 포맷 표를 옮겨 적기
✅ wiki/syntheses/protocol-reverse-engineering.md 에
   "번들에서 perform("submit") 을 찾아 WebSocket 임을 확정했다.
    상세는 docs/programmers-protocol.md §4" 로 기록
```

### 5.2 실패한 시도도 남긴다

되돌아간 접근, 틀린 가설, 헛짚은 진단은 **지우지 않는다.**
포트폴리오 관점에서 이것들이 오히려 실력을 보여준다.

실제 사례:
- `challengeable_id` 와 codes 키를 혼동해 4회 연속 실패한 것
- `partTitle` 만 보고 "DFS/BFS 에 약하다"고 잘못 진단한 것

### 5.3 세션 단위로 적재한다

이 프로젝트는 대화 한 번에 많은 것이 결정된다. 작업이 일단락되면 그 세션을
`raw/sessions/` 에 남기고 결정을 분리 추출한다.
