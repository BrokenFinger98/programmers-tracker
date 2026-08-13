---
type: decision
project: programmers-tracker
tags: [git, bootstrap, security, vault]
author: BrokenFinger98
created: 2026-08-13
updated: 2026-08-13
sources: [raw/sessions/2026-08-13-the-map-becomes-a-workspace.md]
---

# The server prepares the record repository, and the token retires SSH outright

## Context

Installing meant running `bootstrap.md` §2 by hand: copy a template, `git init`, commit, create
a GitHub repository in the browser, wire a remote, and (for pushes) generate a deploy key,
register it, and write a compose override. Forgetting `git init` was not an error — records were
written and never committed, with one warning.

Meanwhile the template had decayed to two README files, because everything else it carried had
moved into the server (`.gitignore` rules #122/#234, `dashboard.base` #255, directories on
demand) — and those READMEs went stale twice in one day, being the one thing the server could
not refresh.

## Options considered

1. **Keep the template, document harder.** Rejected: a template reaches only repositories that
   do not exist yet, which *is* the staleness mechanism.
2. **Host-side one-liner for the remote** (`gh repo create --private --source --push`) with an
   explicit `TRACKER_RECORD_REMOTE` for everyone else. No credential enters the container.
   Recommended first; the owner chose 3.
3. **A GitHub token in `.env`, the server does everything** — init, seed, create a private
   repository, wire origin, store the push credential, push. Chosen by the owner with the
   trade-off stated: one secret replaces the entire SSH path (key generation, deploy-key
   registration, compose override), and the concern — a broad-scope credential resident beside
   the session cookie — was raised and reaffirmed.

## Decision

The server, on boot, in order and each step only when missing: create the directory →
`git init --initial-branch=main` → seed `README.md`, `README.ko.md`, `dashboard.base` → ensure
the four ignore rules → with `GITHUB_TOKEN` set: refresh the
push credential always (that is both the SSH-migration path and token rotation), and with no
`origin` besides: create a **private** repository named after the record directory, wire it, and
let the startup backup carry the first push (`push.default current`). `template/` is deleted; the seeds live once, in
`src/main/resources/vault/`, because the Docker image ships `src` and not the template.

`TRACKER_RECORD_REPO` now defaults in compose exactly as it always has in `application.yml`
(`~/ps-records`) — the one place that was strict — and the path is announced at every boot:
`Records live at <path>`.

## Rationale

Every piece the server now does is smaller than something it already did: it already wrote into
the directory, committed, and pushed. What was left to the user was exactly the part that rotted
(the template) or silently degraded when skipped (`git init`).

The token's containment is structural, not promised:

- **`private` is hardcoded in the request and re-verified in the response.** If GitHub ever
  answers with a public repository, nothing is wired — the failure lands on the wiring, never on
  the privacy.
- **The token cannot reach a log or a remote URL.** `GithubToken` renders masked (the
  `SessionCookie` pattern), and pushes authenticate through `.ps/git-credentials` — owner-only,
  inside the directory the server itself gitignores — so a failed push logged with git's own
  words cannot contain it.
- **An existing `origin` is left alone, with one exception**: a *GitHub* SSH URL is repointed at
  HTTPS when a token exists. That rule was written to protect a deliberate SSH choice — and once
  SSH is retired and `openssh-client` is out of the image, leaving the URL does not respect a
  choice, it guarantees a push that cannot succeed. Same repository, mechanical, and only where
  the token can authenticate; any other host stays untouched.
- **The `.env` line is removable after the first boot**; the credential store carries pushes
  from then on. The resident secret shrinks to a file the repository can never commit.

The 422 path converges instead of failing: a name that already exists is looked up and wired,
behind the same private check — so a crash between creation and wiring heals on the next boot.

## Accepted costs

- **A repo-creating token is broader than this tool needs**, and for its useful window it sits
  beside the session cookie. Chosen by the owner over the host-side one-liner that avoids it;
  the window is one boot.
- **Deleting the seeded README resurrects it.** Editing is respected forever; deletion is read
  as loss, not intent. The rarer intent loses.
- **A defaulted record path means data lands somewhere the user did not type.** Contained by the
  boot announcement and by the default being the path every document already suggested.
- **SSH is retired outright, not demoted** — a further owner call on the same day. The deploy-key
  instructions, the compose mounts and `openssh-client` in the image are all gone; a non-GitHub
  remote is still one `git remote add` away with that host's own credentials, but this tool
  documents exactly one push path. The cost is real for anyone who preferred a
  repository-scoped deploy key over an account token; the gain is that the tool has one story
  and the image carries no tool it cannot use.

## Outcome

Implemented in #258. Verified against a fake GitHub (JDK HttpServer) for every claim about our
requests, and the public-repository refusal is the test named for the nightmare. Live
verification against the real API is the owner's first boot with a token.
