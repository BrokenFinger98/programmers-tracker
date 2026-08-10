// The sensor's job (design §8): announce "this problem is being viewed right now", and
// report the two things only a browser can see (#120).
//
// It sends four fields — two the server cannot work out for itself (#114), and two it can
// never learn at all. No code, no results, no cookies leave this script.
//
// It never talks to the server directly: a content script's cross-origin fetch is subject
// to the page's CORS rules and the local server publishes none, so the request is relayed
// through the service worker, which host_permissions covers.

"use strict";

const HEARTBEAT_MS = 30_000;
const DEBOUNCE_MS = 400;
const TICK_MS = 1_000;

// The lesson number lives in the URL, which is the one place it is always present. The
// `data-lesson-id` attribute this used to read is absent on a problem's sub-pages —
// measured on /lessons/<id>/questions, 2026-08-10.
function lessonId() {
  const found = location.pathname.match(/\/lessons\/(\d+)/);
  return found ? found[1] : null;
}

// The only field that needs the DOM. A language-tab switch replaces this input in place, so
// its `data-language` is what says which tab is open (measured 2026-08-07).
function language() {
  return document.querySelector("input[data-type=code]")?.dataset.language ?? null;
}

// Measured 2026-08-10: the 질문하기 tab is a real navigation to /lessons/<id>/questions, and
// unlike 다른 사람의 풀이 it opens on problems you have NOT solved — which is what makes it
// a usable signal for "was help within reach while stuck".
function onQuestionsPage() {
  return /\/lessons\/\d+\/questions/.test(location.pathname);
}

// --- what only the browser knows ----------------------------------------------------------

// Focused seconds per lesson, kept in extension storage rather than in this page: a language
// switch or a hop to the questions tab reloads the script, and a counter living here would
// restart at zero each time.
const focused = new Map();
let visitedQuestions = new Set();
let counting = null;

function isFocused() {
  return document.visibilityState === "visible" && document.hasFocus();
}

async function load() {
  const stored = await chrome.storage.local.get(["focusedSec", "sawQuestions"]);
  Object.entries(stored.focusedSec ?? {}).forEach(([lesson, sec]) => focused.set(lesson, sec));
  visitedQuestions = new Set(stored.sawQuestions ?? []);
}

function save() {
  chrome.storage.local.set({
    focusedSec: Object.fromEntries(focused),
    sawQuestions: [...visitedQuestions],
  });
}

// One second per second, and only while the tab is actually in front. The record's
// `elapsedSec` is wall-clock since the problem was announced, so a problem opened before
// dinner reads as three hours; this is the number that answers "how long did you work on it".
function tick() {
  const lesson = lessonId();
  if (!lesson || !isFocused()) return;
  focused.set(lesson, (focused.get(lesson) ?? 0) + TICK_MS / 1000);
}

// --- announcing -----------------------------------------------------------------------------

function body() {
  const lesson = lessonId();
  const lang = language();
  // A page with no editor is not a problem being solved, and a channel with no language is
  // not a channel. The questions page falls here, which is why its visit is recorded above
  // rather than announced.
  if (!lesson || !lang) return null;
  return {
    lessonId: lesson,
    language: lang,
    focusedSec: Math.round(focused.get(lesson) ?? 0),
    sawQuestions: visitedQuestions.has(lesson),
  };
}

let lastChannel = null;

// The server is idempotent — a repeat for a channel it already holds answers `refreshed` —
// so a heartbeat costs nothing and is what re-registers after a server restart. It also
// carries the newest counts, which is why the counts are cumulative rather than deltas.
function announce(reason) {
  const payload = body();
  if (!payload) return;
  const channel = `${payload.lessonId}:${payload.language}`;
  if (reason === "changed" && channel === lastChannel) return;
  lastChannel = channel;
  chrome.runtime.sendMessage({ type: "watch", body: payload, reason });
}

// A language-tab switch rewrites the code input in place rather than navigating, and a
// same-page route change swaps the lesson. Both surface as DOM mutations; the debounce keeps
// a burst of them to one announcement.
let pending = null;
const observer = new MutationObserver(() => {
  clearTimeout(pending);
  pending = setTimeout(() => announce("changed"), DEBOUNCE_MS);
});

load().then(() => {
  const lesson = lessonId();
  if (lesson && onQuestionsPage()) {
    visitedQuestions.add(lesson);
    save();
    // Nothing to announce from here — no editor, so no language — but the visit is the point.
    return;
  }
  observer.observe(document.body, { subtree: true, childList: true, attributes: true });
  announce("opened");
  counting = setInterval(tick, TICK_MS);
  setInterval(() => {
    save();
    announce("heartbeat");
  }, HEARTBEAT_MS);
});

// The counts are worth keeping even if the tab is closed between heartbeats.
addEventListener("pagehide", save);
