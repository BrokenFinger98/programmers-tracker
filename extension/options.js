"use strict";

const token = document.getElementById("token");
const port = document.getElementById("port");
const saved = document.getElementById("saved");

chrome.storage.local.get(["token", "port"]).then((stored) => {
  token.value = stored.token ?? "";
  port.value = stored.port ?? 1619;
});

// Saved as you type rather than behind a button: there is no valid intermediate state to
// protect, and a forgotten button is one more way to end up not watching.
function save() {
  const value = Number(port.value);
  chrome.storage.local.set({
    token: token.value.trim(),
    port: Number.isInteger(value) && value > 0 && value < 65536 ? value : 1619,
  });
  saved.textContent = "Saved.";
  setTimeout(() => (saved.textContent = ""), 1500);
}

token.addEventListener("input", save);
port.addEventListener("input", save);
