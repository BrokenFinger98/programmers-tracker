// The sensor's whole job (design §8): announce "this problem is being viewed right now".
// It reads five identifiers from the page and sends nothing else — no code, no results, no
// cookies. It never talks to the server directly: a content script's cross-origin fetch is
// subject to the page's CORS rules and the local server publishes none, so the request is
// relayed through the service worker, which host_permissions covers.

"use strict";

// Measured on lesson 120803 (2026-08-07): all five read exactly like this, and a
// language-tab switch changes `language` and `codesKey` while `challengeableId` stays.
function identifiers() {
  const challengeable = document.querySelector("[data-challengeable-id]");
  const code = document.querySelector("input[data-type=code]");
  const lesson = document.querySelector("[data-lesson-id]");
  if (!challengeable || !code || !lesson) return null;

  const read = {
    lessonId: lesson.dataset.lessonId,
    challengeableId: challengeable.dataset.challengeableId,
    challengeableType: challengeable.dataset.challengeableType,
    language: code.dataset.language,
    codesKey: code.id,
  };
  return Object.values(read).every((v) => v) ? read : null;
}

let lastSent = null;

// The server is idempotent — a repeat for a channel it already holds refreshes recency —
// so a heartbeat costs nothing and is what re-registers after a server restart.
function announce(reason) {
  const read = identifiers();
  if (!read) return;
  const key = JSON.stringify(read);
  if (reason === "changed" && key === lastSent) return;
  lastSent = key;
  chrome.runtime.sendMessage({ type: "watch", body: read, reason });
}

// A language-tab switch rewrites the code input in place rather than navigating, and a
// same-page route change swaps the lesson. Both surface as DOM mutations; the debounce
// keeps a burst of them to one announcement.
let pending = null;
const observer = new MutationObserver(() => {
  clearTimeout(pending);
  pending = setTimeout(() => announce("changed"), DEBOUNCE_MS);
});

const DEBOUNCE_MS = 400;
const HEARTBEAT_MS = 30_000;

observer.observe(document.body, { subtree: true, childList: true, attributes: true });
announce("opened");
setInterval(() => announce("heartbeat"), HEARTBEAT_MS);
