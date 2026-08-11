# 2026-08-07 — seven runners, then four critics that broke the pipeline

Raw session record. Immutable (wiki schema §1).

Back-filled 2026-08-11 from the session transcript (session `b240e44e`), the git history and
the PR record. This is the page that
[[decisions/2026-08-08-run-raw-sessions]] cited before it existed — see
`raw/sessions/2026-08-11-backfilling-the-raw-layer.md`.

Two halves. Until 13:42 the day builds runners for seven languages. After 13:42 four
independent reviewers take the whole system apart, and most of what they found was load-bearing.

---

## Morning — the runner decision, and who generates them

The day opened on a defect from the night before: a submission scored 100 on screen and was
recorded `UNKNOWN`, because a resubmission of identical code returns a **cached result** over
the socket while the browser renders its scoreboard from its own request (protocol §13.2). The
owner's answer to "issue or leave it, since it's documented" was **"해결해야지"** — a documented
behaviour is not an acceptable outcome. Fixed by surfacing the stored reason (#75), not by
reclassifying: `UnknownReason` matches the measured string exactly and the label reaches the
commit subject, the README and MCP.

Then the runner question, which the owner reframed twice:

> 러너는 좀 생각해봐야하는게. 이게 모든 사용자가 intellij를 쓰는거도 아니고 모든 코드가
> java라는 보장도없잖아. 이해했나?

and, when told the alternative was letting the MCP client write the runner files:

> 그냥 서버가 다 알아서 만들게 하고싶은데. 오래걸리고 유지보수 부분이 늘어나도 상관없을거같은데.

That is the decision in [[decisions/2026-08-07-server-generated-runners]]. The owner also set
the scope by measurement rather than preference — *"유명하고 사람들이 알고리즘 풀때 많이
쓰는언어 순서로해봐 … 상위 7개정도"* — settling the order java → python3 → cpp → javascript →
kotlin → c → csharp, shipped as #77, #79, #81, #83, #85, #87, #89.

The governing line for the whole subsystem, and the reason each language took its own PR:
**a runner that compiles, runs, and tests the wrong thing is worse than none.** "Supported" is
earned only by an execution suite that runs the generated artifact against a real toolchain —
so the CI gained a step that fails if the Python execution results file is missing or contains
`<skipped`, because a runner image quietly losing `python3` must not silently un-earn support.

---

## Afternoon — "비판적으로 적대적으로 검토하고"

At 13:42:

> 다음으로 진행할게 뭐가있는데? 비판적으로 적대적으로 검토하고 추가해야되는 기능이나 아직
> 부족한거 더 찾아봐.

Four reviewers ran in parallel, each read-only and each with its own lens: **gap-product**
(design vs built), **critic-security**, **critic-pipeline**, **critic-runners**. What follows is
their findings, kept because the fixes are only half the value.

### critic-pipeline — four CRITICAL

| | Finding | Fate |
|---|---|---|
| **C1** | The ping never reset the silence deadline. `Ping → Step.Ignore` was swallowed inside the flow, so `.timeout(silenceDeadline)` measured the gap between *cable events*, not between pings. An idle channel therefore reconnected every ~15 s **forever** — ~3.5 resubscribe cycles/minute/channel, ~28 connections/minute at eight channels, against Programmers | #106 |
| **C2** | A grading whose `start` frame was missed was discarded whole, **before the raw log**, at DEBUG. Reachable three ways: the C1 reconnect gap, a reconnect after `connectionLost` settled a grading INCOMPLETE, and the extension posting `/watch` after the user already submitted | #113 |
| **C3** | The raw file was moved out of the recovery queue **before** the record line was appended. If the append threw, the log said *"its frames are kept"* and they were kept nowhere anything would look | #105 |
| **C4** | `detectRepository` accepted an **enclosing** repository — the exact case its own comment claimed it prevented, because `rev-parse --git-dir` succeeds from any subdirectory. Reproduced: `git add --all` staged the outer repository's unrelated files, so a startup reconcile would commit and a PASS would push the user's own working tree | #102 |

C1 is worth reading twice. The reviewer's own answer to "is a half-open socket detected within
15 s?" was **yes — but only because the detector fires constantly regardless of socket health.**
A monitor that cannot distinguish "broken" from "quiet" is not a monitor, and it passed for
days because its test stubbed the flow *above* the layer that swallowed the ping.

C4's fix is `rev-parse --show-toplevel` compared against the configured root.

### critic-security — one MAJOR, no CRITICAL

The MAJOR is the one worth remembering: **protocol-supplied example values were injected as raw
code into runners the user then executes.** `ExampleValues` parsed leniently and passed
unparsed text through, so a crafted example became source in the generated JavaScript and
Python runners. Demonstrated, not argued —

```
0)||require('fs').mkdirSync('…/PWNED_PROOF')||solution(0
```

actually created the directory under `node`, while the runner printed a plain `FAIL`. **The
injection was invisible in the runner's own output.** Fixed at the root (#103): a value parses
or it is refused, with a `wellFormed` check that accepts only null, booleans, numbers and
strings, recursively.

The four MINORs were all of one kind — a surface that does less than it appears to: a dead
`cookieHeader()` accessor falsifying `SessionCookie`'s own claim, a credential path built with
`Path.of` while every other path used `ConfiguredPath.of`, `@RequestBody String` materialising
the whole body before the token check, `.env` absent from `.dockerignore`.

The clean list mattered as much: cookie exfiltration traced to every call site, fixtures
genuinely scrubbed, no record data in this repository, no hardcoded personal paths.

### The runner findings, and the false-PASS series

critic-runners produced the ones that close #101, #104 and #111 — every one of them a way for a
runner to report PASS on an answer that was wrong:

- a Java answer of the wrong *shape* still passed
- a file with both a `main` and a declared `solution` was classified as one of them rather than
  as **AMBIGUOUS**, which is the honest answer
- a non-injective `String.valueOf(stringify(...))` fallback made distinct values compare equal
- C could not report a returned length, so the check silently compared a prefix

Each fix moved in the same direction: **refuse rather than guess**, and say why.

---

## The sentence this session should be remembered for

From critic-pipeline's verdict:

> each currently has a test that walks past the defect without asserting on it

Four days later the same pattern was found three more times in one afternoon and finally
written down as [[concepts/tests-that-explain-defects]]. It was named here first, in a review
finding, and nobody extracted it — which is its own instance of the thing this whole back-fill
is about.

---

## Also this day

- `/watch` answering `started` whether or not the subscription works (M1) — **still open**.
  A rejected subscription is log-only, and the log line discards the reason string, so an
  expired cookie is indistinguishable from a flaky network
- Run sessions never leaving `.ps/raw`, every run record's `rawPath` dangling (M2) — deferred to
  the owner because design §5.1 ("run makes no `attempts/` file") and the constitution ("never
  discard originals") pull opposite ways, and satisfying both requires `rawPath` to be nullable,
  which is a schema change. Became [[decisions/2026-08-08-run-raw-sessions]]
- Every state path CWD-relative in production while the `under(recordRoot)` helpers were
  test-only (M3) — became [[decisions/2026-08-10-state-beside-the-records]]
- The sensor extension's first version (#109) and `list_problems` (#110)
- The ping fix (#106) and a docs pass that stopped describing a system that no longer existed
  (#108)
