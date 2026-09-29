"use strict";

const assert = require("node:assert/strict");
const { test } = require("node:test");

const decide = require("./decide.js");

test("only expired and missing call for a credential", () => {
  assert.equal(decide.needsCredential("expired"), true);
  assert.equal(decide.needsCredential("missing"), true);
  assert.equal(decide.needsCredential("alive"), false);
  assert.equal(decide.needsCredential("unknown"), false);
  assert.equal(decide.needsCredential(undefined), false);
});

test("a set of our cookie on our site is handed over; anything else is not", () => {
  const set = (cookie, removed = false) => ({ cookie, removed });
  assert.equal(decide.cookieChangeToPush(set({ name: "_session_production", domain: ".programmers.co.kr", value: "v1" })), "v1");
  assert.equal(decide.cookieChangeToPush(set({ name: "_session_production", domain: "school.programmers.co.kr", value: "v2" })), "v2");
  assert.equal(decide.cookieChangeToPush(set({ name: "_session_production", domain: ".programmers.co.kr", value: "v1" }, true)), null);
  assert.equal(decide.cookieChangeToPush(set({ name: "other_cookie", domain: ".programmers.co.kr", value: "v" })), null);
  assert.equal(decide.cookieChangeToPush(set({ name: "_session_production", domain: "evil-programmers.co.kr", value: "v" })), null);
  assert.equal(decide.cookieChangeToPush(set({ name: "_session_production", domain: ".programmers.co.kr", value: "" })), null);
  assert.equal(decide.cookieChangeToPush(undefined), null);
});

test("a server that lost the cookie is re-supplied with the same value; only an empty one is withheld", () => {
  assert.equal(decide.shouldPush("v1"), true);
  assert.equal(decide.shouldPush("v1"), true);
  assert.equal(decide.shouldPush(null), false);
  assert.equal(decide.shouldPush(""), false);
});

test("a sign-in's burst of identical cookie sets is handed over once; a new value is handed over again", () => {
  assert.equal(decide.changeToPush("v1", null), "v1");
  assert.equal(decide.changeToPush("v1", "v1"), null);
  assert.equal(decide.changeToPush("v2", "v1"), "v2");
  assert.equal(decide.changeToPush(null, "v1"), null);
});
