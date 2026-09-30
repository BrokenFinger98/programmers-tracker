---
type: source
project: programmers-tracker
tags: [credentials, sensor, extension, concurrency, measurement, failed-attempts]
created: 2026-09-30
updated: 2026-09-30
sources: [raw/sessions/2026-09-30-the-sensor-hands-the-session-over.md]
---

# 2026-09-30 session summary — the sensor hands the session over

## Key claims

1. **Trigger 1 measured**: sign-out/sign-in with no manual step → *handed over: changed=true* at
   08:23:09.693 with no `expired` answer before it; the open channel reopened; a submit recorded;
   a restart booted on the cached file.
2. **A sign-in re-sets the same cookie ~100 times in 30 seconds** (103 hand-offs, one new value).
   The change trigger dedupes on the last change seen; the server-asked trigger stays
   unconditional — the first cut's once-per-value memory had blocked recovery, and that is why.
3. **Chrome delivers `cookies.onChanged` only to a host permission that covers the cookie's own
   domain.** `school.programmers.co.kr` alone never fires for a `.programmers.co.kr` cookie;
   `*.programmers.co.kr` does. This explained a silent re-login the afternoon before.
4. **`{"cookie": null}` was written into the credential file as text** by the first cut of the
   endpoint — `JsonNull` is a `JsonPrimitive`. Strings only and cookie-octets only now.
5. Two defects found beside the work shipped the same morning: the registry's single-writer
   assumption ([[decisions/2026-09-30-the-registry-has-one-lock]], five entries in a capacity of
   four) and one probe per concurrent caller ([[decisions/2026-09-30-one-probe-in-flight]]).

## Pages this source updated

[[decisions/2026-09-29-the-sensor-hands-over-the-session]] ·
[[decisions/2026-09-30-the-registry-has-one-lock]] · [[decisions/2026-09-30-one-probe-in-flight]] ·
[[concepts/assumption-vs-measurement]] · [[concepts/tests-that-explain-defects]]
