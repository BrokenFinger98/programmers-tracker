# Contributing

Thanks for your interest! This project records Programmers solving history via
passive ActionCable observation. Before contributing, two things to know:

1. **YAGNI is constitutional here.** The tool's owner is a job-seeker whose time
   budget is the real constraint — most feature ideas are declined. Open an issue
   first; PRs for undiscussed features are usually closed.
2. **The protocol is private and unstable.** Protocol claims require measured
   evidence — "should work" is not accepted; "verified on lesson X on date Y" is.

## Dev setup

- JDK 21 (Kotlin/Spring — arrives with Phase 1), `gh` CLI
- One-time after clone: `git config core.hooksPath .githooks`
  (Claude Code users get this automatically via a SessionStart hook)

## Flow

issue → branch `<type>/<issue#>-<slug>` → PR → **squash merge** (branch auto-deleted).
Direct pushes to `main` are blocked by branch protection. Conventional Commits,
English only — code, comments, commits, docs.

## The wiki push gate (you will meet it)

`.githooks/pre-push` blocks any branch push whose range contains no
`docs/llm-wiki/` change. This repo treats *recording decisions* as part of the
work: if your branch decided anything, add an ADR under
`docs/llm-wiki/wiki/decisions/` (see existing ones for the format). For
genuinely record-free changes (typo fixes), add an auditable trailer:
`git commit --amend --no-edit --trailer 'Wiki-Skip: <reason>'`.

## AI-assisted PRs are welcome

This project is itself AI-assisted. Requirements: say so in the PR (checkbox in
the template), include what you verified yourself and how, and make sure you can
explain every line — "the model wrote it" is not a review answer.

## Security / privacy

Session cookies, emails, and personal solving history flow through this tool.
Never commit any of those; test fixtures must be scrubbed (see
development-rules §7). Vulnerabilities: email the owner (profile) instead of
opening a public issue.
