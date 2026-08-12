# 2026-08-12 — verifying the pipeline against an empty repository

Raw session record. Immutable (wiki schema §1).

The owner, mid-sweep: *"앞선 수많은 테스트를 통해 record repo에 잘못된 데이터들이 너무 많을거같은데
한번 싹 삭제하고 아예 처음부터 다 다시 e2e 현재 상태 기준으로 build 다시해서 띄워서 테스트."*

---

## The premise was right, and measurable

57 records, and the counting is the argument:

| | |
|---|---|
| null `score` — the field #193 wired through | 49 / 57 |
| null `sincePrevSec` — #207 | 40 |
| verdicts #212 now classifies differently | 3 |
| orphaned gradings | 2 |

Not one record in the repository had been produced by the build under test. A defect fixed a
week ago and a defect still present look identical in that data, which makes it evidence of
nothing.

**Archived, not deleted.** Committed and tagged `archive/2026-08-12-pre-clean-slate` and pushed
before anything was removed. Two things checked before touching it: none of the eight problems
is independent practice — all Lv0/Lv1, first record two days after the project started, every
one driven by a verification run — and the credentials are not in that repository at all. The
session cookie and `/watch` token live in the project's own `.ps/`.

Stop the container first: `AttemptAuthority` and `SubmissionGaps` are restored from the log at
startup and advanced in memory, so wiping under a running server leaves the counters ahead of
an empty log.

---

## Result

Fifteen records, build `b3d68cc`, seven languages each broken and passed on purpose, plus one
wrong answer.

Everything the sweep was for held: seven of seven compile failures classified (including the
three #212 fixed), seven of seven passes carrying `score` and `rating`, no unresolved verdicts,
`sincePrevSec` on every record that has a predecessor, the attempt sequence monotonic 0→8
across seven languages, eight runner files, and **`incompleteHistory` absent from every MCP
answer** — a field that only disappears when the history has no holes, so #215 and the clean
slate verify each other.

The WRONG verdict was exercised end to end for the first time. It carries `score: 0.0/100.0`,
which is worth knowing: a score is not evidence of a pass.

`review_queue` returned nothing, correctly — every pass is minutes old and the first interval
is 60 days.

---

## Two findings

**`attempt 0` on the very first record.** Reported as a defect for about a minute. It is
`AttemptAuthority.NONE = 0`, documented as *"a run before the first submit belongs to no attempt
file"* — invisible until the log is empty, and correct.

**#217 — every language switch warns that broadcasts may have been lost.** Seven switches, seven
`WARN … JobCancellationException … anything broadcast meanwhile is lost`. The cancellation is
ours, issued to move to the next language's channel — the ordinary path
[[decisions/2026-08-11-a-pass-belongs-to-its-language]] exists for. Same shape as #215 one day
apart: a warning that fires on an ordinary action gets trained away, and this one shares a log
with reconnect warnings that mean something.

---

## The browser mechanic, pinned after three sessions of guessing

Clicks had been failing intermittently since 2026-08-11, and I had two theories, both wrong:
*"the first click after a navigation is swallowed"* and *"a click needs a hover first"*. Each
explained some of the data.

The actual rule: **click coordinates resolve against the last screenshot.** A batch that
navigates and then clicks is aiming at the previous page's frame. `hover` appeared to help only
because it forced a fresh frame first.

```
[navigate, wait, screenshot] → [setValue, hover, click, wait, screenshot]
```

Every batch ends with a screenshot so the next one has coordinates to aim at.

Worth recording above the mechanic: **twice in this session I concluded "capture is broken"
from a missing record without looking at the screen.** The second time I went as far as
checking the server, the subscription and the extension before taking a screenshot that showed
the run had simply never started. A negative observed through your own setup is not an
observation — the same sentence [[concepts/tests-that-explain-defects]] already carries, and it
cost twenty minutes anyway.
