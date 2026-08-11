# 2026-08-12 — the improvement loop turns on its own work

Raw session record. Immutable (wiki schema §1).

Continuation of the autonomous loop the owner asked for: *"시험모드 말고 계속 개선해 문제점 스스로
찾고 개선하고 스스로 찾고 개선하고"*. What is worth recording from this stretch is **where the
defects came from**: not from tests failing, but from reading the project's own record of what it
does not know.

---

## The guard caught its author, one day later

Writing this page was not a choice. `scripts/guards.sh` §7 — added the previous day (#171) —
refused the push:

```
FAIL  a log.md ingest line claims a session that was never saved:
  2026-08-12  is logged as ingested and has no raw session
  Write the raw session, or drop the log line. The log entry is not the record.
```

The failure it was built for is *"the visible half of the ritual survives, the substance stops"*,
and the first person it stopped was the one who had written it. That is the whole argument for a
guard over a habit.

**A second finding came with it, and it is worse.** The guard failed and the push went through
anyway:

```bash
git add -A && ./scripts/guards.sh 2>&1 | tail -2 && git commit ... && git push
```

`./scripts/guards.sh | tail -2` takes **`tail`'s** exit status, so the `&&` chain saw success. The
pre-push hook checks the wiki gate and not the guards, so nothing else stopped it. A gate piped
into anything is not a gate.

---

## #191 — a 200 is not proof of a session

Found by reading **§14 Unverified Items**, not by a test.

`SessionActivityProbe` mapped `200 → ALIVE`, on the stated reasoning that the status is the whole
signal and the body should not be parsed. §14 records that this API family throttles as
**200-with-an-HTML-error-page** rather than 429. A rate limit would have read as *"your session
is fine"*.

And the ADR written hours earlier claimed the endpoint

> returns JSON in both states, so it also **avoids** the 200-with-HTML throttling shape

I measured 200-with-JSON and 401-with-JSON. **I never triggered a throttle on it.** "Avoids" was
an inference wearing the clothes of a measurement — inside the ADR that corrects an earlier
instance of exactly that error. Fourth occurrence in two days of *observation real, quantifier
invented*.

**Deliberately not measured.** Triggering a rate limit means hammering Programmers to prove a
property we can simply stop claiming (development-rules §9.3). The body is shape-checked instead:
correct either way, one `startsWith("{")`.

---

## #193 — two defects protecting each other

76 of 76 real records: `score: null`, `rating: null`, including algorithm submits that passed
18/18. The values were in the measured fixture all along —

```json
"userScore": "100.0", "perfectScore": "100.0",
"isNewRating": true, "oldUserRating": 1000, "newUserRating": 1001
```

— parsed into `SubmitMessage`, and dropped at `GradingFrameFacts`, which had no field for any of
it. The record's KDoc, the class KDoc and the object mother all implied otherwise.

Then the test found something older. The SQL assertion was written from
`SubmissionRecord.score`'s KDoc:

> Null for every database grading — the SQL path reports no score (protocol doc §6).

**It failed.** `sql-pass.jsonl` carries `userScore`/`perfectScore`, and so does §6's own example.
That KDoc had been wrong since it was written, and nothing caught it because the field was null
for *every* grading anyway — a wrong explanation of a right observation, kept alive by a second
defect.

Neither is visible from the other's side. Read the KDoc against the records and it is confirmed;
read the mapper and a field nothing fills looks like an unimplemented feature; read the fixtures
and the object mother agrees with the KDoc.

What broke it was **writing the test from the documentation and letting it fail**. Writing it
from the fixture — the more natural move, the fixture being open on the next screen — would have
passed and taught nothing.

---

## The shape of this stretch

Three of the four defects fixed here came from **reading what the project says about itself**:

| source | found |
|---|---|
| §14 Unverified Items | the probe trusting a status this API is documented to lie with |
| an ADR's own "accepted costs" | the boot-only warning (#185), the `stats`-only field (#187), the unnoticed `UNKNOWN` (#189) |
| a KDoc, disagreed with by a measured fixture | the score that never crossed the boundary, and the reason given for a null |

None came from a failing test, because a test cannot fail about a wire that was never built.
Writing the remaining risk down honestly is what produced the next work item every time — the
list of accepted costs turned out to be a backlog.
