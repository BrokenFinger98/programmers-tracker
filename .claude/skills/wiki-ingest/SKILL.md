---
name: wiki-ingest
description: 대화·결정·결과물을 이 레포의 Wiki(docs/llm-wiki)에 적재·정리한다. 사용자가 "적재/정리/기록해줘"라고 하거나 작업이 일단락되어 중요한 결정·결과물·재사용 노하우가 나왔을 때 사용. 잡담·일회성은 제외.
---

# wiki-ingest

Wiki 경로: **이 레포의 `docs/llm-wiki/`** (레포 루트 기준 상대경로 — 개인 절대경로 금지).

당신은 이 위키의 **편집자**다. `docs/llm-wiki/CLAUDE.md` 스키마를 반드시 따른다.

## 절차
1. **스키마 로드** — `docs/llm-wiki/CLAUDE.md` 와 `docs/llm-wiki/index.md` 를 읽는다.
2. **소스 확정** — 인자가 있으면 우선. 없으면 현재 대화에서 다시 볼 가치가 있는 것만 추린다
   (중요 결정 · 작업 결과물 · 재사용 노하우 · 실측 결과 · **틀렸던 가설**).
3. **raw 저장** — `docs/llm-wiki/raw/sessions/YYYY-MM-DD-<제목>.md` (불변, 수정 금지).
   같은 경로가 있으면 `-2` suffix.
4. **위키 통합 (덮어쓰기 금지, merge)** — 기존 페이지는 본문에 녹여 갱신
   (`updated:` 오늘로, 새 raw 를 `sources:` 에 추가. 충돌 시 옛 내용 `⚠️ (구) ...` 보존).
   새 페이지는 `wiki/` 하위에 스키마 frontmatter 를 갖춰 생성.
   - ★ **결정은 1건 = 1파일**: `wiki/decisions/<YYYY-MM-DD>-<slug>.md`, `author`·`created`·`updated` 필수.
   - ★ **ADR 형식**: 맥락 / 검토한 선택지 / 결정 / 이유 / **받아들인 비용** / 결과.
5. **프로토콜 사실은 복제하지 않는다** — `docs/programmers-protocol.md` 가 유일한 출처.
   위키에는 *어떻게 알아냈고 무엇을 결정했는가* 만 쓰고 문서 절을 참조한다.
6. **교차링크 + index** — `[[...]]` 연결 + 새 페이지를 `index.md` 에 등록 (고아 금지).
   append 항목은 **날짜로 시작**해 머지 시 시간순 정렬되게 한다.
7. **모순 점검** — 충돌은 `⚠️` 로 최신 기준 정리.
8. **로그** — `docs/llm-wiki/log.md` 에
   `## [YYYY-MM-DD] ingest | <제목> → N pages updated, M created` append.
9. **커밋** — 레포 루트에서 `git add docs/llm-wiki && git commit`.

## 이 프로젝트 고유
- **실측 근거를 반드시 인용한다.** "그럴 것이다"와 "확인했다"를 구분해 쓴다.
- **실패한 시도·틀린 진단도 남긴다.** 포트폴리오 관점에서 가치가 있다.
- 결정 페이지는 **그 자리에 없던 사람이 읽어도 이해되게** 쓴다.
