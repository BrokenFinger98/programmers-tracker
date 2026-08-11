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
  const badge = { watching: "●", recorded: "✓", unclear: "?", blind: "!", missing: "!", failed: "×" }[state];
  const colour = {
    watching: "#2d7", recorded: "#2d7", unclear: "#96f", blind: "#d33", missing: "#e90", failed: "#d33",
  }[state];
  chrome.action.setBadgeText({ text: badge });
  chrome.action.setBadgeBackgroundColor({ color: colour });
  // White regardless of the browser's theme: Chrome picks a contrast colour on its own, and
  // on the orange it picks black, which reads as a disabled control rather than a warning.
  chrome.action.setBadgeTextColor({ color: "#fff" });
  chrome.action.setTitle({ title: `programmers-tracker sensor — ${detail}` });
}

// What the server actually wrote down, which is the question the badge could not answer
// before (#156). A page announcing a correct answer and a server recording nothing looked identical
// from the toolbar, and a passing submit went missing for twenty minutes on 2026-08-11.
//
// A recorded failure is `recorded`, not a warning. This tool exists to record failures; a red
// mark there would teach the user to read their own wrong answers as a broken sensor.
//
// `unclear` is the state that did not exist. It means the server wrote a record it could not
// classify — the exact shape of that lost submit — and it is the only one worth alarming on.
// Whether the server is observing this problem *right now*, which `status` never said: it
// answers `started` for a subscription the judge refused, so a stale session cookie looked
// exactly like a working sensor while every grading was lost (#167).
//
// This outranks the record state on purpose. A `✓` from an hour ago is true and irrelevant if
// nothing is being watched now — the question the badge answers is "will my next submit be
// recorded", and the honest answer here is no.
function subscriptionState(subscription) {
  if (subscription === "rejected") {
    return ["blind", "the judge refused the subscription — your session cookie has expired. Replace .ps/session and it heals without a restart"];
  }
  if (subscription === "unreachable") {
    return ["blind", "the server cannot hold this problem's channel open — it is retrying. Nothing you solve right now is being recorded"];
  }
  return null;
}

function recordState(last) {
  if (!last) return ["watching", ""];
  const when = agoOf(last.at);
  const classified = last.verdict != null;
  const what = `${last.action} ${last.verdict ?? last.outcome} ${last.passed}/${last.total}`;
  if (!classified) return ["unclear", ` — last recorded ${when}: ${what}, which the server could not classify`];
  return ["recorded", ` — last recorded ${when}: ${what}`];
}

function agoOf(iso) {
  const seconds = Math.round((Date.now() - Date.parse(iso)) / 1000);
  if (!Number.isFinite(seconds) || seconds < 0) return "just now";
  if (seconds < 90) return `${seconds}s ago`;
  if (seconds < 5400) return `${Math.round(seconds / 60)}m ago`;
  return `${Math.round(seconds / 3600)}h ago`;
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
      const blind = subscriptionState(answer.subscription);
      if (blind) {
        report(blind[0], `lesson ${answer.lessonId} in ${answer.language} — ${blind[1]}`);
        return;
      }
      const [state, note] = recordState(answer.lastRecord);
      report(state, `watching lesson ${answer.lessonId} in ${answer.language} (${answer.status})${note}`);
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
