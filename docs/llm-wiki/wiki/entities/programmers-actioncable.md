---
type: entity
project: programmers-tracker
tags: [programmers, actioncable, websocket]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md]
---

# Programmers ActionCable

What the Programmers judge actually is. `docs/programmers-protocol.md` is the single
source of truth for the facts; only its character is recorded here.

- Address `wss://ws.programmers.co.kr:443/cable`, subprotocol `actioncable-v1-json`
- Channels split by problem type — algorithm `Challenge::AlgorithmChannel`,
  SQL `Challenge::DatabaseChannel`
- **Field naming differs per channel** — algorithm camelCase, SQL snake_case.
  The parser must accept both; if this asymmetry climbs into the domain, every layer
  gets contaminated
- **SQL never sends `finish`** — waiting on `finish` to detect termination hangs forever

Actions: `run` · `submit` · `save` · `stop` · `reset` · `finish`.
`run` is the only path that yields full error text without appearing in the submission history.

→ [[concepts/actioncable-broadcast-observation]] · [[concepts/verdict-classification]]
