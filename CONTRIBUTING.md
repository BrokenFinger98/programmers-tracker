# Contributing

Thanks for your interest! This project records Programmers solving history via
passive ActionCable observation. Before contributing, two things to know:

1. **YAGNI is constitutional here.** The maintainer's time is the binding
   constraint, so most feature ideas are declined on cost rather than on merit.
   Open an issue first; PRs for undiscussed features are usually closed.
2. **The protocol is private and unstable.** Protocol claims require measured
   evidence — "should work" is not accepted; "verified on lesson X on date Y" is.

## Dev setup

- **JDK 25** and `gh` CLI. The Gradle wrapper handles everything else; `java -version`
  must say 25, and a 21 will fail the build before you have written a line
- One-time after clone: `git config core.hooksPath .githooks`
  (Claude Code users get this automatically via a SessionStart hook)

Before pushing, all four must exit 0 — CI runs these same files:

```bash
./scripts/check.sh && ./scripts/test.sh && ./scripts/build.sh && ./scripts/guards.sh
```

## Flow

issue → branch `<type>/<issue#>-<slug>` → PR → **squash merge** (branch auto-deleted).
Direct pushes to `main` are blocked by branch protection. Conventional Commits,
English only — code, comments, commits, docs.

**Do not rename a CI job.** `main` requires six named status checks and GitHub matches
them by the job's *display name*, so a rename produces a check that is required, expected
and never arrives — `main` stays unmergeable until branch protection is updated to match.
Nothing in the workflow file says so. Adding a job is fine; renaming one is a two-place
change and the second place is a repository setting only the owner can edit.

## Documentation is held to the same standard as code

`README.md` states what is built in exactly one place — its *What works today* table — and
every other section describes the design. When you land a feature, flip its row there; do
not add a second claim elsewhere. `scripts/guards.sh` checks the mechanical half of this:
every repository path a maintained document names must exist. The semantic half — whether a
sentence is still true — is on the reviewer. For a path that is deliberately not built yet,
put `guard:planned` on that line rather than removing the check.

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
