# 기록 보존 체계 설계 — 결정 단일화 · 위키 계층화 · push 게이트

> 2026-08-04 · 브레인스토밍 확정안. 구현 전 사용자 리뷰 대상.
> 관련: [`CLAUDE.md`](../../../CLAUDE.md)(헌법) · [`docs/llm-wiki/CLAUDE.md`](../../llm-wiki/CLAUDE.md)(위키 스키마)

---

## 1. 문제 (전부 실측)

1. **증류가 강제되지 않는다.** 세션 원문은 auto-compact 후에도 디스크에 남는다
   (실측: 230MB transcript 가 PreCompact 5회 이상 겪고도 온전). 그러나 원문 → 위키 증류를
   강제하는 장치가 없다. 전역 위키 실측: **한 달간 75개 세션(2.4GB) 적체, ingest 0회.**
   "나중에 원본에서 하지"는 실패가 실증된 전략이다. 증류는 맥락이 신선할 때가 가장 싸다.
2. **결정 기록이 이미 두 벌이고, 첫날부터 어긋났다.** `.harness/state/decisions.md` 6건 vs
   `docs/llm-wiki/wiki/decisions/` ADR 5건 — "저장소 2개·양쪽 public" 결정이 위키에 없다.
3. **전역/프로젝트 위키가 역할 구분 없이 충돌한다.** 전역 SessionStart 리마인더가 이 레포
   안에서도 전역 inbox 카운트를 주입해, 이 레포의 `/wiki-ingest`(다른 대상)를 잘못 유도한다.
4. **compact 후 state 재주입이 없다.** 세션 앞부분이 압축되면 goal·progress·결정 맥락을
   잃은 채 진행하게 된다.

## 2. 결정 요약

| # | 결정 | 요지 |
|---|---|---|
| D1 | 위키는 레포 안 유지 + 3계층 모델 | raw=개인 PC(자동) · 1차 증류=레포 위키 · 2차 증류=전역 위키(별건) |
| D2 | 결정 기록 = 위키 단일 권위 | `.harness/state/decisions.md` **폐지**. `wiki/decisions/` ADR 이 유일한 출처 |
| D3 | 전역/프로젝트 역할 분리 | 전역 리마인더만 자진 후퇴(가드 2줄). 전역 아카이브 훅은 유지 |
| D4 | push 게이트 = **git native pre-push** | push 범위에 위키 변경 없으면 차단. `Wiki-Skip:` 트레일러로 탈출 |
| D5 | compact 복구 훅 | SessionStart 훅이 goal·progress·위키 인덱스를 재주입 |

## 3. 상세

### 3.1 D1 — 위키 계층 모델

| 계층 | 내용 | 위치 | 채워지는 방식 |
|---|---|---|---|
| Raw | 모든 세션 원문 | 개인 PC (`~/.claude/projects` + 전역 inbox) | 전역 훅 자동 (실증 완료) |
| 1차 증류 | 프로젝트 결정·개념·전말 | 이 레포 `docs/llm-wiki/` | `/wiki-ingest` + D4 게이트 강제 |
| 2차 증류 | 범프로젝트 일반화 | 전역 위키 `~/Desktop/llm-wiki` | **별건** — 전역 정비 시 |

원칙은 위키 스키마 §5.1 의 일반화: **한 사실은 한 곳에, 나머지는 참조.**
프로젝트 지식은 레포에 산다 — 포트폴리오(스키마 §0 이중 목적) · 클론 공유 · 게이트 강제
· 코드와 수명 일치. 전역 위키는 공개 불가(raw·타 프로젝트 혼재)이므로 공유 목적지가 될 수 없다.
전역 위키에 프로젝트 레지스트리 페이지를 두는 것은 전역 정비(별건)로 미룬다.

### 3.2 D2 — 결정 기록 단일화

- `.harness/state/decisions.md` **삭제**. 삭제 전 5건의 내용이 대응 ADR 에 전부
  포함돼 있는지 대조(parity check)하고, 부족분은 ADR 에 merge.
- 마이그레이션 신설 1건: `wiki/decisions/2026-08-04-two-public-repos.md`
- 이번 설계 자체의 ADR 신설: `decisions-live-in-wiki` · `wiki-push-gate` ·
  `global-project-wiki-split` (각 1파일, ADR 형식 — 검토한 선택지·비용 포함)
- **세션 시작 읽기 순서 변경**: `goal.md` → `progress.md` → `docs/llm-wiki/index.md` 의
  Decisions 절 (1결정 1줄 — 충돌 감지용 스캔. 충돌 의심 시 해당 ADR 열람)
- state 의 의미 재정의: **state = 위치**(어디까지 왔나: goal·progress),
  **위키 = 지식**(무엇을 왜 결정했나·노하우)
- 갱신 파일: `CLAUDE.md`(세션 시작 절·Architecture 절·State 운영 절·보고 형식·Quality Gate),
  `README.md` 83행, `.gitignore` 주석. `development-rules.md` 는 변경 없음
- 게이트 존재/품질 분리: **게이트(D4)는 존재만 검사**, ADR 품질은 `/wiki-lint`·PR 리뷰가 맡는다

### 3.3 D4 — push 게이트 (git native pre-push)

**메커니즘 선택.** Claude `PreToolUse` deny 안과 비교해 native 를 골랐다:

| 기준 | native pre-push | PreToolUse |
|---|---|---|
| 터미널 직접 push | ✅ 잡는다 | ❌ 못 잡는다 |
| push 범위 파악 | ✅ stdin 으로 정확한 ref 범위 수신 | ⚠️ HEAD 기준 추정 |
| 도구 중립 | ✅ CC·Codex·수동 전부 | ❌ Claude Code 전용 |
| GitHub/GitLab | ✅ git 레벨 — 플랫폼 무관 | ✅ 동일 |

차단 시 stderr 는 Bash 도구 결과로 모델에게 그대로 보이므로, 모델이 읽고
`/wiki-ingest` 후 재시도하는 흐름이 성립한다.

**알고리즘** (`.githooks/pre-push`, 커밋 대상):

```
for each (local_ref local_sha remote_ref remote_sha) in stdin:
  local_sha == 0000...     → skip           # 브랜치 삭제 push
  remote_sha == 0000...    → range = local_sha --not --remotes  # 신규 브랜치
  else                     → range = remote_sha..local_sha
  range 에 docs/llm-wiki/ 변경 있음        → pass (stderr 로 /wiki-lint 권고)
  range 내 커밋에 "Wiki-Skip: <사유>" 트레일러 → pass (사유 stderr 표기)
  else → exit 1 + "push 범위에 위키 변경 없음. /wiki-ingest 먼저,
         예외면 커밋 트레일러 Wiki-Skip: <사유>"
```

**fail-open 원칙.** 게이트는 *무의식적 누락* 방지 장치이지 보안 장치가 아니다.
git 명령 실패·예상 밖 입력은 전부 pass. 의도적 우회(트레일러 남용)는 PR 리뷰가 잡는다.

**설치.** `git config core.hooksPath .githooks` (레포 로컬 설정).
- 이 환경: 전역 `core.hooksPath` 없음 확인 — 충돌 없음
- 자동화: D5 의 SessionStart 훅이 같은 스크립트에서 멱등 실행
- 수동 폴백: README 에 1커맨드 안내 (Claude Code 미사용자·타 도구)

### 3.4 D5 — compact 복구 훅

프로젝트 훅 (`.claude/settings.json` + `.claude/hooks/inject-state.sh`, 커밋 대상):

- `SessionStart` (matcher 없음 = startup·resume·clear·compact·fork 전 소스):
  `goal.md` + `progress.md` + `docs/llm-wiki/index.md` 를 `additionalContext` 로 주입.
  없는 파일은 조용히 skip (클로너는 goal.md 없음 — 정상)
- 같은 스크립트가 `core.hooksPath` 멱등 설정 (3.3)
- `CLAUDE.md` 세션 시작 절 문구 조정: "훅이 주입한다. 주입이 안 보이면 직접 읽는다"
  (타 도구 사용자 폴백)

### 3.5 D3 — 전역 쪽 변경 (레포 밖 · 이 PR 에 포함 불가)

`~/.claude/hooks/wiki-remind.sh` 에 가드 추가: **cwd 레포에 `docs/llm-wiki/` 가 있으면
조용히 exit 0.** 훅은 설정 계층 간 merge 되어 프로젝트에서 전역 훅을 끌 수 없으므로,
전역 스크립트의 자진 후퇴가 유일한 방법이다.
`wiki-archive-session.sh` · `wiki-archive-precompact.sh` 는 **수정하지 않는다**
(raw 계층 담당 — 이 프로젝트 세션도 전역 inbox 에 계속 쌓인다. 2026-08-04 실증).

## 4. 산출물

| 구분 | 파일 |
|---|---|
| 신설 | `.githooks/pre-push` · `.claude/hooks/inject-state.sh` · ADR 4건 (`two-public-repos` 마이그레이션 + 설계 3건) |
| 수정 | `.claude/settings.json`(훅 등록) · `CLAUDE.md` 5개소 · `README.md` · `.gitignore` 주석 · `docs/llm-wiki/index.md`(ADR 등록) · `docs/llm-wiki/log.md` · `.harness/state/progress.md` |
| 삭제 | `.harness/state/decisions.md` (parity check 후) |
| 레포 밖 | `~/.claude/hooks/wiki-remind.sh` 가드 (별도 수동 적용) |

## 5. 검증 계획

셸 훅은 TDD 짝 규칙(.kt 대상)의 형식 적용 대상이 아니므로 아래 시나리오 검증으로 갈음한다.

1. **단위 시뮬레이션** — stdin 에 ref 라인을 피딩해 분기 전수 확인:
   위키 변경 있음(pass) · 없음(block) · `Wiki-Skip` 트레일러(pass+사유 출력) ·
   브랜치 삭제(skip) · 신규 브랜치(fallback range) · git 명령 실패(fail-open pass)
2. **E2E** — scratchpad 에 bare repo 를 origin 으로 둔 클론에서 실제 `git push` 왕복:
   차단 → ingest 커밋 추가 → 통과. `--dry-run` 의 pre-push 발화 여부도 여기서 실측
3. **실환경 1회** — 이 설계의 구현 브랜치 자체가 첫 실전 통과 사례가 된다
   (ADR 4건이 위키 변경이므로 게이트를 자연 통과 — dogfooding)
4. **inject-state** — JSON 피딩으로 additionalContext 출력 확인 + goal.md 없는 상태 skip 확인

## 6. 하지 않는 것

- **PR 시점 lint 강제 훅** — MR 을 웹 UI 로 만드는 경우(GitLab 관행) 잡을 수 없어 플랫폼
  종속 우회가 된다. push 통과 시 stderr 권고로 대체
- **전역 inbox 75건(2.4GB) 정리 · 전역 ingest 스킬 · 프로젝트 레지스트리** — 전역 위키
  정비 별건. 이 레포의 범위가 아니다
- **CI 게이트 · wiki-merge 스킬** — YAGNI (위키 스키마 §4 와 동일 판단)

## 7. 받아들인 비용 · 남은 위험

- **클로너 마찰**: hooksPath 1커맨드 또는 CC 훅 승인 필요. README 로 완화. 미설치 시
  게이트 없이 동작(기능 저하일 뿐 파손 아님 — fail-open 철학과 일치)
- **`Wiki-Skip` 남용 가능**: 기계 게이트는 존재만 보므로, 남용은 PR 리뷰·`/wiki-lint` 가 잡는다
- **게이트 회피 경로 존재**: 비 HEAD refspec push 등 관행 밖 형태는 통과한다. 의도된 한계
  (누락 방지 장치이지 우회 방지 장치가 아니다)
- **전역 raw 와 프로젝트 위키의 이중 존재**: 의도된 중복 — 계층이 다르다(raw vs 증류).
  내용 중복이 아니므로 decisions.md 문제와 다르다
- **`.claude/settings.json` 훅의 신뢰 프롬프트**: 클론 직후 CC 가 훅 실행 동의를 묻는다.
  공개 레포로서 훅 스크립트를 짧고 읽기 쉽게 유지해 검토 가능성을 확보한다
