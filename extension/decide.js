// The decisions behind the session hand-off (#332), and nothing else: no fetch, no chrome API.
// Loaded by the service worker with importScripts and by `node --test` as a module — the same
// file, so what is tested is what ships.
"use strict";

const SESSION_COOKIE = "_session_production";
const SESSION_HOST = "school.programmers.co.kr";
const SESSION_DOMAIN = "programmers.co.kr";

const decide = {
  SESSION_COOKIE,
  SESSION_HOST,

  // The states in which the server records nothing and a cookie from the browser is the cure.
  // `unknown` is not one of them: a probe that could not run says nothing about the cookie.
  needsCredential(session) {
    return session === "expired" || session === "missing";
  },

  // The value to hand over for a cookies.onChanged event, or null when it is not our cookie,
  // not our site, or the cookie was removed rather than set. The cookie is scoped to
  // `.programmers.co.kr`, not to the school subdomain — which is also why the manifest's host
  // permission is `*.programmers.co.kr`: Chrome delivers onChanged for a cookie only to an
  // extension whose host permissions cover the cookie's own domain.
  cookieChangeToPush(change) {
    const cookie = change && change.cookie;
    if (!cookie || change.removed) return null;
    if (cookie.name !== SESSION_COOKIE) return null;
    const domain = String(cookie.domain || "").replace(/^\./, "");
    if (domain !== SESSION_DOMAIN && !domain.endsWith("." + SESSION_DOMAIN)) return null;
    return cookie.value || null;
  },

  // Any non-empty value is handed over, every time the server asks for it. There is deliberately
  // no "already sent" memory on this path: the first cut had one, and it blocked the repair when
  // the server had lost the cookie — it kept asking, the sensor kept refusing to re-send the same
  // value. An equal value costs the server nothing (no write, no probe), so re-sending is free.
  shouldPush(value) {
    return Boolean(value);
  },

  // The change trigger is different: a sign-in re-sets the same cookie on many responses in a
  // row — measured 2026-09-30, about a hundred onChanged events in thirty seconds, one of them
  // carrying a new value — so a change is handed over only when the value differs from the last
  // change seen. The server-asked path above stays unconditional, so recovery is unaffected.
  changeToPush(value, lastChangeSeen) {
    return value && value !== lastChangeSeen ? value : null;
  },
};

if (typeof module !== "undefined") module.exports = decide;
