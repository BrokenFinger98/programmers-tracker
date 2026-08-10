// The sensor's whole job (design §8): announce "this problem is being viewed right now".
//
// It sends two fields, because two is all the server cannot work out for itself (#114) —
// the channel identifiers are properties of the problem and the server reads them off its
// page. No code, no results, no cookies leave this script.
//
// It never talks to the server directly: a content script's cross-origin fetch is subject
// to the page's CORS rules and the local server publishes none, so the request is relayed
// through the service worker, which host_permissions covers.

"use strict";

const HEARTBEAT_MS = 30_000;
const DEBOUNCE_MS = 400;

// The lesson number lives in the URL, which is the one place it is always present. The
// `data-lesson-id` attribute this used to read is absent on a problem's sub-pages —
// measured on /lessons/<id>/questions, 2026-08-10.
function lessonId() {
  const found = location.pathname.match(/\/lessons\/(\d+)/);
  return found ? found[1] : null;
}

// The only field that genuinely needs the DOM. A language-tab switch replaces this input in
// place, so its `data-language` is what says which tab is open (measured 2026-08-07: the
// switch changes the input, the problem's own identifiers stay).
function language() {
  return document.querySelector("input[data-type=code]")?.dataset.language ?? null;
}

function identifiers() {
  const lesson = lessonId();
  const lang = language();
  // A page with no editor — the questions list, say — is not a problem being solved, and
  // announcing it would subscribe to a channel with no language.
  return lesson && lang ? { lessonId: lesson, language: lang } : null;
}

let lastSent = null;

// The server is idempotent — a repeat for a channel it already holds answers `refreshed` —
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

observer.observe(document.body, { subtree: true, childList: true, attributes: true });
announce("opened");
setInterval(() => announce("heartbeat"), HEARTBEAT_MS);
