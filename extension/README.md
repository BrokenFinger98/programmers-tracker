# Sensor extension

Its whole job (design §8): tell your local server **which problem you are looking at**. It
reads five identifiers from the page and sends nothing else — no code, no results, no
cookies, and it never contacts Programmers.

Without it you must register every problem by hand through DevTools and `curl`, and repeat
that after each language-tab switch and each server restart. That was the single biggest
gap between the tool as designed and the tool as it existed (#97).

## Install

Chrome or any Chromium browser, unpacked — this is not on any store:

1. Open `chrome://extensions`, turn on **Developer mode**
2. **Load unpacked**, choose this `extension/` directory
3. Open the extension's **options** (the toolbar icon → ⋮ → Options), paste the contents of
   `.ps/watch-token` from your record repository, and set the port if you changed
   `TRACKER_PORT`

Then open any Programmers problem. The toolbar badge is the status:

| Badge | Meaning |
|---|---|
| green, empty | watching — the server accepted the announcement |
| orange `!` | no token configured yet |
| red `×` | the server refused or could not be reached; hover for its own message |

## What it sends

One `POST /watch`, two fields:

```json
{ "lessonId": "120803", "language": "java" }
```

That is everything the server cannot work out for itself (#114). The lesson number comes
from the URL — the one place it is always present, including on a problem's sub-pages — and
the language from the code editor's `data-language`, which is the only part that needs the
DOM. The channel identifiers are properties of the problem, so the server reads them off
its page and caches them.

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
| content script on `school.programmers.co.kr/learn/courses/*/lessons/*` | reads the five identifiers |

There is no `tabs` permission, no history access, and no remote code.

## What is measured, and what is not

The five selectors were **read off a live problem page** (lesson 120803, 2026-08-07), and a
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
