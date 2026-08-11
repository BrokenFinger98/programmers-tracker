# 2026-08-06 — the record repository, the shipped catalog, and the shape problem

Raw session record. Immutable (wiki schema §1).

Back-filled 2026-08-11 from the session transcript (session `b240e44e`), the git history and
the PR record. See `raw/sessions/2026-08-11-backfilling-the-raw-layer.md`.

Sixteen PRs, from the overnight run that started at 00:31 through to 23:55. The day has three
threads: making the record repository real, deciding what the server is allowed to know about
problems it has never seen, and the discovery that Programmers has **two problem shapes**.

---

## Thread 1 — the record repository stopped being a diagram

The owner's opening questions were the ones a first user asks, and none of them had a clean
answer yet:

> intellij runner가 뭐야? 그리고 그 git 저장소에 push 된다고 했는데 그 repo가 뭐야?? 내가
> 원하는 레포로 세팅하려면 환경변수로 넣어줘야되는건가???

What came out of it:

- `TRACKER_RECORD_REPO`, with the docker/native split stated plainly (`~` expands natively, not
  in `.env`), and the repository itself created by the user from `template/ps-records`
- **Two instances must not share one record repository** (#51). The owner's instinct was to
  forbid it by construction ("docker로만 기동하게 하면 안되나???"); the answer landed on a
  kernel lock in `.git/` plus a change-based heartbeat, because a bind-mounted filesystem
  cannot always be trusted to hold `FileChannel.tryLock`
- SSH push actually working from inside the container (#57 — `openssh-client` was missing, so
  the documented push path had never once run)
- A running instance says which build it is (#70). This mattered later: on 2026-08-10 an
  extension showed an error field the current server does not even read, because the container
  was four days old
- LICENSE, twice (#53 then #68). The owner chose their legal name over the GitHub handle, and
  asked the right question about it — *"실명이나 내 github name이나 어차피 신원 보증은 안되는거
  아니야?"* Correct: neither is identity verification. The reason to use the legal name is that
  MIT's warranty disclaimer is a statement by a person

Late in the day: **"records에 push가 되고있는거야?? 보면 전혀 push 된게 없는거 같은데"** — the
first time the owner checked the output rather than the process, and it is the same instinct
that eventually caught the empty raw layer.

---

## Thread 2 — the catalog, and how close to crawling we were willing to get

Three candidate features were reviewed against development-rules §9.3 (courtesy toward
Programmers). Two were dropped on the owner's instruction; the third became the catalog.

The owner's own framing was the resolution:

> 3번은 문제 자체를 스냅샷한다기보다 한번 문제 전체를 너가 훑어서 문제의 유형이랑 이런걸
> 라벨링해서 문제 제목이랑 id 그리고 라벨링한 문제 유형 데이터만 좀 우리가 가지고 있고 그거
> 쓰면 문제 되려나???

That is a different artifact from a crawl: **not their content, our labels over their
identifiers.** Titles, ids, levels, tags — no problem statements, no test data, no solutions.

The assistant argued for deferring it (YAGNI: build it when a feature needs it). The owner
overruled, and the reason was about sequencing, not scope:

> 이거보다는 지금 미리 하는게 좋을거같은데. 지금 당장 id, title, level, 유형 이거 구해오자.
> orchestration으로 sonnet으로 구해오게 시켜도될거같은데. 그리고 그동안에 너는 밀려있는
> 이슈들 해결해야지.

Which was right on both counts — the labelling is embarrassingly parallel and does not need the
main context, and the catalog is a **once-ever** artifact (ADR
[[decisions/2026-08-06-shipped-problem-catalog]]), so building it late buys nothing.

Two corrections during the run:

- **"야야 지금 너무 분류 종류가 적은거 아니야? solved.ac로부터 다시 분류 정보 더 디테일하게
  가져와."** The first tag set was too coarse. The re-fetch measured **229** tags where the
  design document had said 180 — the document was updated to the measurement, not the reverse
- **"제목도 포함해. 위치는 배포 되야될거같은데. resources에 넣자."** — the catalog ships inside
  the jar. It is not user state, it is not refreshed, and a user who clones must not have to
  regenerate it

689 problems landed in #65.

### The question that has no clean answer yet

> mcp 만으로는 마치 ai의 skill 등록한거처럼 ai가 우리가 모은 데이터를 읽고 적절하게 우리가
> 원하는 방향으로 분석하고 추천해줘야하는데 이거는 mcp가 하는 역할이 아니라서 방향 설정이나
> 강제가 안되면 사용자들이 많이 아쉬울거같은데 … mcp의 응답에 일종에 프롬프트 같은걸 주면
> 되려나?

MCP hands over data; it cannot make the client reason a particular way. Putting instructions in
tool responses is the available lever and it is a weak one. Recorded here unresolved, because
it is the gap between "the records are exposed" and "the diagnosis is good", and no code has
closed it. It is also the boundary CLAUDE.md draws when it forbids rule-based analyzers in the
server: interpretation is the client's job, so the quality of the interpretation is not ours to
guarantee — only the quality of what we hand over.

---

## Thread 3 — Programmers has two problem shapes

The owner found this by looking at problems rather than at code:

> 어떤 문제는 프로그래머스에서 애당초에 main 함수를 들고있고 어떤 문제는 그냥 solution
> 함수로 되어있는 경우가 있어.
>
> 보통 main 형문제는 입출력 문제인 경우가 대부분인거같아. 그래서 직접 Scanner나
> BufferedReader로 읽는 거같아.

That is the whole `ProblemShape` design in two sentences, and it arrived before any runner code
existed — which is why the runner work the next day could be per-shape from the start instead
of being retrofitted. Measured and written up as the main-style run shape and its three traps
(#64, protocol doc).

The second observation the same evening was the other half:

> 이렇게 실행 결과로 뱉어내는 거로 테스트 케이스 추출 가능하지 않나?

The run console renders `입력값 / 기댓값 / 실행 결과` per example. Those are the problem's own
published examples, arriving over the socket in the grading frames — which is what became
`examples.json` and, from there, the input to every generated runner. **The user pointed at the
data source before the feature that needed it existed.**

---

## Verification, and a note on how it was asked for

> 귀찮은데 너가 직접 브라우저 조작해서 확인해봐. 아니다 파이프라인 검증까지 서버 띄워서 해봐.
> 지금 ssh 세팅 되어있지? push 까지 되는거지? docker로도 한번 해보고, 그냥 서버 기동해서도
> 한번 해봐.

Both paths were driven live: native run+submit produced a PASS commit that pushed; Docker
run+submit produced a WRONG commit that correctly did **not** push. Two behaviours, both
observed rather than argued. This is the same instruction pattern that on 2026-08-11 found five
defects the test suite could not — the owner asks for the thing to be *run*, not reviewed.
