# Security

This tool runs on your machine, holds your Programmers login, and writes your solving history to a
repository it pushes. That is a small surface, and all of it is yours — which is why what follows
is specific rather than a template.

## Reporting something

**Please do not open a public issue with a working reproduction in it.**

Use GitHub's private vulnerability reporting if this repository offers it — *Security* tab →
*Report a vulnerability*. If it does not, open an issue that says only that you have a report and
asks for a channel, and leave the details out of it.

There is no bounty, no SLA and one maintainer. What you will get is an answer.

## What this holds, and where

Three secrets, in three places, on purpose.

| | What it is | Where it lives |
|---|---|---|
| **Session cookie** | Your Programmers login | `.ps/session` in this repository's checkout — never in the record repository |
| **`/watch` token** | Generated; gates the endpoint the browser extension calls | `.ps/watch-token`, same place, owner-only |
| **GitHub token** | Optional, first boot only; creates and wires the record repository | `.env`, then a credential store at `<records>/.ps/git-credentials`, owner-only |

**Two of them deliberately do not live with the records.** The record repository is pushed; a
credential inside it would be pushed too, so `session` and `watch-token` stay in the checkout
([#126](https://github.com/BrokenFinger98/programmers-tracker/issues/126)). The GitHub credential
is the exception that proves it: it sits under `<records>/.ps/`, which the server adds to that
repository's `.gitignore` itself rather than trusting one to already be there.

None of the three may appear in a log line, an exception message or a debug dump at any level.
That is a rule in [`CLAUDE.md`](CLAUDE.md) rather than a habit, and the value classes are shaped so
that a lazy string interpolation cannot leak one — `SessionCookie` and `GithubToken` both render
masked.

## Posture

Stated where it is implemented rather than repeated here:

- **Network** — the published port binds the host loopback, and a bind address *inside* a
  container is not the control it looks like: [`compose.yaml`](compose.yaml) and
  [`decisions/2026-08-06-container-network-posture`](docs/llm-wiki/wiki/decisions/2026-08-06-container-network-posture.md).
- **MCP** — token required, any request carrying an `Origin` header refused, and the query side
  physically cannot append or commit, so a prompt-injected *"delete my failures"* has no path to
  act on: [`docs/mcp.md`](docs/mcp.md#security-posture) and
  [`decisions/2026-08-06-mcp-read-slice`](docs/llm-wiki/wiki/decisions/2026-08-06-mcp-read-slice.md).
- **Your records** — the repository the server creates is `private` in the request *and*
  re-verified private in the response before anything is wired to it; an answer that comes back
  public wires nothing:
  [`decisions/2026-08-13-the-server-prepares-the-repository`](docs/llm-wiki/wiki/decisions/2026-08-13-the-server-prepares-the-repository.md).
- **This repository** — `scripts/guards.sh` fails the build on a committed record, a
  session-cookie-shaped string or the live `/watch` token, and the pre-push hook runs it.

## What is not a vulnerability here

Said plainly so nobody spends a weekend on a decision:

- **The `/watch` token is a gate, not an authentication system.** It stops another process on your
  machine from posting to the endpoint by accident. It is not a defence against something that can
  already read your filesystem — which could read the token.
- **Anything with local filesystem access has already won.** The session cookie is a file; it has
  to be. There is no key derivation that helps when the attacker can read the key.
- **The records repository is private by construction, not by permission checks.** The server never
  makes it public and refuses to wire one that is. What you do with it afterwards is yours.
- **Reading someone else's records is not in scope**, because there is no multi-user surface: one
  process, one learner, one directory.

## The protocol question is a different question

This tool reads a private protocol that Programmers never published or promised. That is a matter
of courtesy and of terms, and [`README.md`](README.md#principles-toward-programmers) takes a
position on it — no auto-submission, no traffic interception, requests at the level a browser
makes, and compliance if asked to stop.

It is not a security vulnerability, and filing it as one makes both conversations harder. If you
think the position itself is wrong, that is worth an issue on its own.

## Supported versions

There are no releases yet. `main` is the supported version; a fix lands there.
