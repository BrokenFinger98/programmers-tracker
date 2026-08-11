// The only component that talks to the server. It exists because a content script's
// cross-origin fetch is subject to the page's CORS rules, which the local server does not
// publish — and deliberately should not, since granting them would let any page in the
// browser reach it. host_permissions covers the service worker instead.

"use strict";

const DEFAULTS = { port: 8080, token: "" };

async function settings() {
  return { ...DEFAULTS, ...(await chrome.storage.local.get(Object.keys(DEFAULTS))) };
}

// The badge is the whole user interface. A sensor that fails silently is the failure mode
// this extension exists to remove: the user believes a solve is being recorded when it is
// not (#97, and the /watch-always-200 gap it does not fix).
//
// Every state carries a glyph, including the good one. `watching` used to be the empty
// string with a green background, and a badge background is painted *behind its text* — so
// nothing was drawn at all and the one state meaning "it is working" was the only one with
// no visual. A working sensor and an unloaded one looked identical (#147).
function report(state, detail) {
  const badge = { watching: "●", missing: "!", failed: "×" }[state];
  const colour = { watching: "#2d7", missing: "#e90", failed: "#d33" }[state];
  chrome.action.setBadgeText({ text: badge });
  chrome.action.setBadgeBackgroundColor({ color: colour });
  // White regardless of the browser's theme: Chrome picks a contrast colour on its own, and
  // on the orange it picks black, which reads as a disabled control rather than a warning.
  chrome.action.setBadgeTextColor({ color: "#fff" });
  chrome.action.setTitle({ title: `programmers-tracker sensor — ${detail}` });
}

async function watch(body) {
  const { port, token } = await settings();
  if (!token) {
    report("missing", "no token yet. Open the extension's options and paste .ps/watch-token");
    return;
  }
  try {
    const response = await fetch(`http://127.0.0.1:${port}/watch`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Tracker-Token": token },
      body: JSON.stringify(body),
    });
    if (response.ok) {
      const answer = await response.json();
      report("watching", `watching lesson ${answer.lessonId} in ${answer.language} (${answer.status})`);
      return;
    }
    // The server's error contract carries a stable machine code and a message written for
    // a person; both are worth showing rather than a bare status number.
    const failure = await response.json().catch(() => ({}));
    report("failed", `${response.status} ${failure.error ?? ""} — ${failure.message ?? "no detail"}`);
  } catch (cause) {
    report("failed", `cannot reach the server on port ${port} — is it running?`);
  }
}

chrome.runtime.onMessage.addListener((message) => {
  if (message?.type === "watch") watch(message.body);
  // No response is sent: the content script does not wait, so returning true here would
  // leave a channel open for a reply that never comes.
});
