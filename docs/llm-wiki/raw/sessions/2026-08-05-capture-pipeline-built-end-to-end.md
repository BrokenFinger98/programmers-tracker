# 2026-08-05 (afternoon–night) — the capture pipeline, built end to end

Raw session record. Immutable (wiki schema §1).

Back-filled 2026-08-11 from the session transcript (session `b240e44e`), the git history and
the PR record. See `raw/sessions/2026-08-11-backfilling-the-raw-layer.md` for why this was
written six days late and what that costs it.

The morning of this day — stack selection, the Boot 4 upgrade, the adversarial design review —
is `raw/sessions/2026-08-05-design-review-and-stack-upgrade.md`, ingested at 12:56. **This page
covers everything after that**, which is most of the capture half of the product: #14 through
#40, eleven merged PRs between 13:14 and 23:42.

---

## What the owner asked for before any code was written

The design review came out of one instruction, and it is worth quoting because it set the
standard for the rest of the project:

> 지금 이 통신 인벤토리에 대해서 … 모든 통신 인벤토리 및 로직들에 대해서 전체적으로 내가
> 고민한것처럼 기술적 고민을 할 필요가 있어. 엄청 꼼꼼하게. 그리고 queue가 아니라 더 좋은
> 방법이 있는지 찾아보고. 엄청 꼼꼼하게 적대적 검증해봐.

The inventory in question was 2 inbound paths, 4 outbound, 3 local — and the owner's own worked
example was the one that mattered: *is a write queue needed?* They had reasoned it out
themselves (writes arrive at run/submit; reads are many but harmless) and then asked for the
same treatment applied to everything else, adversarially. That produced the design revision in
#8/#9 and, indirectly, every finding on this page.

---

## The eleven

| # | What landed |
|---|---|
| 14 | CI: three-OS matrix, ktlint, jacoco. The owner asked for jacoco explicitly and agreed to fail-under thresholds |
| 16 | Session assembly and verdict classification |
| 18 | The record write path — `submissions.jsonl`, append-only |
| 20 | `CodeFetch`, and the measurement that closed gate 1 |
| 22 | `/watch` and the subscription registry |
| 23 | The cable connection attached — **the first end-to-end record** |
| 27 | Watchdog, reconnect, startup reconciliation |
| 24 | Identity types moved to `domain`; message knowledge stays in `protocol` |
| 29 | Protocol messages reach `application` as domain facts, not events |
| 32 | CI hardened so it can stand in for a reviewer |
| 34 | Derived artifacts — solution files, diffs, README |
| 39 | Git commit and push on pass, retrying only what can heal |

The two refactors (#24, #29) are the architecture the project still has, and both were done
*before* there was much code to move — which is the only reason they were cheap.

---

## Four findings that outlived the day

All four are in [[concepts/assumption-vs-measurement]] in more detail; this is where they
happened.

**1. A measurement that never became a fixture (#16).** A worker reported honestly that the
algorithm *run* success path had no fixture and was therefore untested end to end. True of the
fixtures, false of the project — those frames had been captured live twice. Without the
fixture, nothing showed that run testcases identify themselves by 0-based `index` rather than
`testcaseId`, the mapper declined them, a run collected **zero** testcases, and the session
would have settled `UNKNOWN` on the most common user action there is.

**2. The two-trial elimination (#20).** *Does `run` save the code?* If it does not, every run
record silently attaches the previous code. The first trial (hash unchanged after editing,
changed after pressing Run) could not separate "run saved it" from "a debounced autosave fired
in that window". The second trial cost three minutes — edit, then **wait without running** —
and killed the confound, because a debounce short enough to explain trial one would have fired
during trial two. The payoff: design §4.4's main-world editor-buffer injection became
unnecessary and was deleted.

**3. Git rewrote the evidence (#20 CI).** The captured page excerpt parsed on macOS and Linux
and failed on Windows: GitHub's runners check out with `core.autocrlf=true`, so the raw
newlines inside the captured attribute became CRLF. Nothing was wrong with the parser — **the
version control system had edited the measurement in transit**. Fixed in `.gitattributes`
(`fixtures/** -text`) plus a test asserting no CRLF, so a future removal fails by name.

**4. Eight tests that never ran (#20/#21).** A Kotlin test written as an expression body
returns the last expression; when that is not `Unit`, JUnit skips the method **silently** — no
error, no skip notice, no report entry. Eight `ProblemPageCodeFetcher` tests reached `main`
through a green three-OS CI having never executed, including the one asserting the session
cookie never appears in a failure message. The PR had claimed "failure paths tested".

The guard is structural: a Gradle task run as `finalizedBy` on `test` fails when a class
declaring `@Test` produces no result file. It was negative-tested by reintroducing the defect —
and then *itself* verified wrongly twice, the second time passing locally on stale result files
from an earlier run while failing in CI on all three OSes. A verification that can succeed
without the thing it verifies having happened is not a verification.

---

## Where the owner pushed back

Two of the day's frictions were about the assistant, not the code.

- **"ci 왜 계속 깨지지?"** (17:43) — CI broke repeatedly, and the pattern was the same each
  time: a last edit after the final local gate run.
- **"머지 완료. 너 ci 모니터링 재대로 못하는거 같은데??"** (21:27) — the assistant was
  reporting CI as green from a stale check or the wrong run. This is the origin of the habit of
  watching CI by commit SHA rather than by PR.

---

## The instruction that changed the shape of the project

At 22:17, home from work:

> 나 집 도착했어. 내가 자는 동안 너가 스스로 issue 만들고 개발해서 pr 올리고 ci 모니터링하고
> merge and delete branch 하면서 내가 자는동안 계속 개발할 수 있어?? 스스로 ci 강화도 해야될거
> 같으면 ci 강화도 하고

Everything from here to 2026-08-11 runs under that authorization. Guardrails settled at the
time and honoured since: merge only on fully green CI; hold contract changes (the constitution,
the design's data model, ADR reversals) for the owner; never claim unverified live behaviour;
stop after repeated CI failures on one issue.

It is also the origin of a cost that came due five days later — with the owner asleep, the
person who would have asked "did you actually run the wiki skill?" was not in the room.
