# 2026-08-12 — two workers that never started

Raw session record. Immutable (wiki schema §1). Recovered 2026-08-13 from the pre-compaction
transcript snapshot (`raw/inbox/precompact-…-b240e44e.jsonl`, window 17:15–17:44 KST) — the tag-map
build night's session page records what was built; this records the twenty-eight minutes of
orchestration that failed before it, which no page had kept.

---

## The setup

The tag-map plan (#229) had six tasks. The owner invoked Orca orchestration; a run and a task
covering plan Tasks 1–2 were created, and a claude worker was dispatched into the current
worktree at 17:25.

Sequencing was deliberate: Task 2 cannot compile without the `TagCount` type Task 1 creates.

## Worker 1: dispatched, `ready`, and never started

For fifteen minutes the worker reported `ready` with no heartbeat progress and no files. The
`check --wait` on `worker_done` sat silent. Reading the **worker's terminal directly** — not its
state — showed the cause at 17:42:

> 긴 여러 줄 프롬프트가 TUI 입력창에서 깨졌습니다 — 줄바꿈이 Enter로 먹히면서 문장이 조각나
> 입력칸에 남아 있고, 에이전트는 시작조차 안 했습니다. Orca 문제가 아니라 제가 보낸 프롬프트
> 모양 문제입니다.

A multi-line task spec had been split by the composer: each newline submitted a fragment, the
remainder sat unsent in the input box, and the agent under it had never received a task at all.
**The dispatch layer reported `ready` the entire time.** The supervisor's own error, and invisible
to every status surface — only the terminal's raw contents said it.

## Worker 2: a clean one-liner, delivered intact, no response

Stopped worker 1, re-created the task as a single-line spec, dispatched at 17:42. The terminal
this time showed the TASK block delivered whole — and still no response within the check window.
No error, no heartbeat, nothing to diagnose. The cause was not established, and deliberately not
guessed.

At 17:43 the owner ended it:

> 또 시작 안하고있는거 같은데. 아 그냥 너가 직접 해라

Direct execution began at 17:43 (`Task 1: 계산기, 테스트 먼저`), RED confirmed at 17:44. The six
tasks that followed inline are the tag-map session page's story.

## In the same window: an over-claim, corrected

At 17:38 the owner asked whether sequencing was because worktree merges are slow. The answer on
record at 17:39:

> 아니요, 그건 제 설명이 부정확했습니다. **병합 시간이 진짜 이유가 아닙니다.** 진짜 이유는
> **의존성**입니다. […] 다만 제가 "6개가 서로의 산출물 위에 쌓인다"고 뭉뚱그린 건
> **과장이었습니다.**

Tasks 1/3/6 were in fact independent; 1→2 and the 4→5 chain were not. The blanket claim was
retracted with the actual dependency table, and the owner accepted the sequential plan on the
corrected grounds ("아니야. 너가 잘 판단해서 하고있네").

## The supervision checklist that was ready and never ran

Written at 17:39, while waiting on a worker that would never answer — kept because it is the
checklist for next time:

1. **Did it actually pass through RED** — the step a worker most easily skips; check commit order
   and test content, not the claim.
2. **`guards.sh` passes** — especially Korean in comments, which had been caught twice that day.
3. (The wait itself was on `worker_done`, backgrounded, 15-minute window.)

Also from the window: `check` streams multiple JSON objects, so `json.load` on its output breaks —
the wait was valid, the parsing was not, and deliveries replay until acked, so nothing was lost.

## What this cost and what it bought

Twenty-eight minutes, no code. What it bought, in exchange and on record: the composer constraint
(one-line specs, or files the worker reads), the state-vs-terminal lesson (`ready` is what the
dispatch layer believes, the terminal is what is true), and the stopping rule — after one
diagnosed failure and one undiagnosable one, the third attempt is direct execution, not a third
dispatch.
