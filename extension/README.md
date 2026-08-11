# Sensor extension

**[한국어](README.ko.md)**

Its whole job (design §8): tell your local server **which problem you are looking at**, and
report the two things only a browser can see. It sends four fields and nothing else — no
code, no results, no cookies — and it never contacts Programmers.

Without it you must register every problem by hand through DevTools and `curl`, and repeat
that after each language-tab switch and each server restart. That was the single biggest
gap between the tool as designed and the tool as it existed (#97).

## Install

Chrome or any Chromium browser, unpacked — this is not on any store:

1. Open `chrome://extensions`, turn on **Developer mode**
2. **Load unpacked**, choose this `extension/` directory
3. Open the extension's **options** (the toolbar icon → ⋮ → Options), paste the contents of
   `.ps/watch-token` from **this repository's checkout** — not your record repository; it is
   a credential and the record repository is pushed (#126) — and set the port if you changed
   `TRACKER_PORT`

Then open any Programmers problem. The toolbar badge is the status:

| Badge | Meaning |
|---|---|
| green `●` | watching — the server accepted the announcement, and nothing is recorded for this problem yet |
| green `✓` | the last grading on this problem **was recorded**, whatever its verdict |
| purple `?` | recorded, but the server could not classify it — `UNKNOWN` or `INCOMPLETE` |
| red `!` | the server is up, but **this problem is not being observed** — hover for which credential to fix |
| orange `!` | no token configured yet |
| red `×` | the server refused or could not be reached; hover for its own message |
| no badge | the content script never ran — you are not on a problem page, or the extension is not loaded in this profile |

Hover for the detail: the verdict, the testcase counts and how long ago.

**A recorded wrong answer is `✓`.** The badge answers "is the tool working", not "did you
pass" — recording a failure is this tool doing its job, and a red mark there would teach you
to read your own wrong answers as a broken sensor.

**`?` is the one worth stopping for.** It means the server wrote a record it could not
classify, which is what a grading looks like when it goes missing. On 2026-08-11 a passing
submit was recorded as `UNKNOWN` and nothing on screen disagreed with anything else; the loss
was silent for twenty minutes (#154). This badge is the disagreement.

**Red `!` outranks everything else.** `/watch` used to answer `started` whether or not the
subscription lived, so a channel that never connected produced a green badge and a 200 on every
heartbeat (#167). The server now reports the socket's own verdict, and a channel that is
unreachable or refused says so — a `✓` from an hour ago is true and irrelevant if nothing is
being watched now.

⚠️ **It does not catch an expired session cookie**, and nothing currently does. Measured
2026-08-11 (#175, protocol §15.3): an unauthenticated subscription is **confirmed in half a
second and pinged normally**, and simply receives no broadcasts. Every liveness signal a passive
observer has is identical between a working session and a dead one, so the badge stays green
while nothing is recorded. If your records stop appearing while the badge looks healthy, replace
`.ps/session` — that is the failure this badge cannot see.

Every state has a glyph, the good one included. It used to be green with no text, and a badge
background is painted behind its text — so nothing was drawn and a working sensor looked
exactly like an unloaded one (#147). "No badge" now means what it says.

The state comes from the heartbeat, so it can lag a grading by up to thirty seconds. That is
fine for a signal whose job is verification, and it is why there is no spinner: a progress
indicator would need to watch the page, which this extension deliberately does not do.

## What it sends

One `POST /watch`, four fields:

```json
{ "lessonId": "120803", "language": "java", "focusedSec": 612, "sawQuestions": false }
```

The first two are what the server cannot work out for itself (#114). The lesson number comes
from the URL — the one place it is always present, including on a problem's sub-pages — and
the language from the code editor's `data-language`, which is the only part that needs the
DOM. The channel identifiers are properties of the problem, so the server reads them off
its page and caches them.

The other two are what the server can never learn at all (#120). `focusedSec` counts only the
seconds this tab was visible **and** focused, which is the number that answers "how long did
you work on it" — the record's own `elapsedSec` is wall-clock since the problem was announced,
so a problem opened before dinner reads as three hours. `sawQuestions` says the questions tab
was opened while you were stuck. Both feed `review_queue` (§6.4).

A page with no editor announces nothing: the questions list is not a problem being solved.

Sent when the page opens, whenever those values change, and every 30 seconds. The server is
idempotent — a repeat answers `refreshed` rather than `started` — which is what re-registers
the problem after a server restart.

## Why the request goes through the service worker

A content script's cross-origin `fetch` is subject to the page's CORS rules, and the local
server publishes none — deliberately, since permissive CORS would let any page in your
browser reach it. So `sensor.js` reads the DOM and hands the body to `background.js`, whose
`host_permissions` cover `127.0.0.1` and which is therefore allowed to make the request.

## Permissions, and why each is needed

| Permission | Why |
|---|---|
| `storage` | remembers your watch token and port |
| `host_permissions: http://127.0.0.1/*` | the only place it ever sends anything |
| content script on `school.programmers.co.kr/learn/courses/*/lessons/*` | reads the lesson number and the open language tab |

There is no `tabs` permission, no history access, and no remote code.

## What is measured, and what is not

The selectors were **read off a live problem page** (lesson 120803, 2026-08-07), and a
language-tab switch was measured to change the language while the problem's own identifiers
stay, and on 2026-08-10 the rewritten reader was run against three live pages — a problem
page, a SQL problem, and a questions page with no editor, which correctly reads as nothing.
The request contract was exercised against a running server:
`started`, then `refreshed` on the repeat, and `401` with the error body the badge shows.

**Loaded and seen to work, 2026-08-10.** Chrome, unpacked, token pasted, a problem page
opened: the badge went green reading `watching lesson 181947 in java (refreshed)`, and the
server logged the same build answering. The manifest wiring, the service-worker relay and
the badge are proven, not merely written.

One thing that first attempt caught, and it is worth knowing: the badge read
`400 INVALID_REQUEST — challengeableId is missing` — a field the current server does not
look at. The container was four days stale. `docker compose up -d --build`, and read the
`Running build …` line the server prints on startup.
