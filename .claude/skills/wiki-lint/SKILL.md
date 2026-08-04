---
name: wiki-lint
description: 이 레포의 Wiki(docs/llm-wiki)를 건강검진·정리한다 — 모순·낡은 정보·고아 페이지·빠진 링크·index 불일치. 사용자가 "위키 정리/점검/린트"를 요청할 때 사용.
---

# wiki-lint

Wiki 경로: **이 레포의 `docs/llm-wiki/`**. `docs/llm-wiki/CLAUDE.md` 스키마를 기준으로 삼는다.
범위는 인자로 좁힐 수 있다 (비우면 전체).

## 점검 항목
1. **모순** — ① `sources:` 확인 → ② `updated:`(없으면 `created:`)가 최신인 쪽을 캐노니컬,
   옛 주장은 `⚠️ (구) ...` 보존 ③ 판단 불가면 `⚠️ 수동확인 필요` 로 보고 (임의 판단 금지).
2. **낡은 정보** — 새 소스로 무효화된 옛 결론 갱신/폐기.
3. **고아 페이지** — 인바운드 `[[링크]]` 없는 페이지 연결/통합. (`sources/` stub 은 예외)
4. **빠진 교차링크** — 의미상 직접 관련된 곳만 보강. 과링크 금지.
5. **index 일치** — `index.md` ↔ `wiki/` 실제 파일 1:1.
6. **frontmatter** — `type`·`project`·`updated`·`sources` 보강.
7. **프로토콜 복제 점검** — 위키가 `docs/programmers-protocol.md` 의 사실을 복제하고 있으면
   참조로 바꾼다. 복제는 반드시 어긋난다.
8. **결정 페이지 형식** — ADR 6개 절(맥락/선택지/결정/이유/받아들인 비용/결과)이 갖춰졌는지.
   "받아들인 비용"이 비어 있으면 보완을 요청한다.

## 마무리
변경 요약 보고 → `docs/llm-wiki/log.md` 에 `## [YYYY-MM-DD] lint | <요약>` append
→ 레포 루트에서 `git add docs/llm-wiki && git commit`.
