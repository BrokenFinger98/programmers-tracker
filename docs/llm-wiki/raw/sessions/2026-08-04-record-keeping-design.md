# 2026-08-04 기록 보존 체계 설계 세션

> raw 큐레이션 기록. 세션 원문(transcript)이 아니라 사람이 다시 볼 가치가 있는
> 사실·결정·틀린 가설의 증류다. 원문은 개인 PC 전역 아카이브에 있다.

## 실측 사실

1. **auto-compact 는 디스크 transcript 를 지우지 않는다.** 230MB transcript 가
   PreCompact 5회 이상을 겪고도 온전히 append 유지 중임을 확인. 잃는 것은 원본이
   아니라 *증류* 다.
2. **넛지만으로 증류는 일어나지 않는다.** 전역 위키 inbox: 한 달간 75개 세션(2.4GB)
   적체, ingest 0회 — 리마인더는 매 세션 떴다.
3. **이중 기록은 하루 만에 발산했다.** `.harness/state/decisions.md` 6건 vs
   `wiki/decisions/` 5건 ("저장소 2개" 결정 누락).
4. **훅은 설정 계층 간 merge 된다** (user/project/local 이 전부 실행) — 프로젝트
   설정으로 전역 훅을 끌 수 없다. 전역 스크립트의 자진 후퇴가 유일한 가드 방법.
5. **전역 아카이브 훅은 cwd 무관 발화한다.** 이 세션의 실제 transcript 로
   `wiki-archive-session.sh` 를 실행 → 전역 inbox 에 551k 파일 생성 확인 (직후 삭제,
   실파일은 세션 종료 시 생성됨).
6. git `%(trailers:key=X,valueonly)` 포맷·bash 5.3·jq 가용 확인. 전역
   `core.hooksPath` 미설정 — 레포 로컬 설정과 충돌 없음.

## 내린 결정

[[decisions/2026-08-04-decisions-live-in-wiki]] ·
[[decisions/2026-08-04-wiki-push-gate]] ·
[[decisions/2026-08-04-global-project-wiki-split]] ·
[[decisions/2026-08-04-two-public-repos]] (state 에만 있던 결정의 이관)

## 틀렸던 가설 (보존 — 스키마 §5.2)

- **"전역 wiki-ingest 스킬이 이름 충돌로 잡힌 것"** — 오진. 전역에는 wiki-* 스킬이
  아예 없다. 실제 원인은 전역 *훅* 의 경로 하드코딩이었다. 스킬 개명(ptw-ingest)은
  아무것도 고치지 못했을 것이다.
- **"아카이브 훅을 cwd 기반 분기로 고치자"** — 폐기. 프로젝트 위키에는 inbox 개념이
  없고(소비자 부재), 공개 레포 워킹트리에 세션 원문(쿠키·이메일 포함)을 떨구는
  경로를 새로 뚫는 제안이었다.
- **"compact 되면 대화가 소실된다"** — 절반만 사실. in-context 는 압축되지만 디스크
  원본은 남는다. 문제 정의가 "원본 보존"에서 "증류 강제"로 바뀌었고 설계 전체가
  그에 따라 달라졌다.

## 산출물

스펙 `docs/superpowers/specs/2026-08-04-record-keeping-design.md` (커밋 8958fe4) ·
구현 계획 `docs/superpowers/plans/2026-08-04-record-keeping.md`
