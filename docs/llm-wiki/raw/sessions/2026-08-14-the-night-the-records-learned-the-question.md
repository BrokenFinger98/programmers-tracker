# 2026-08-14 — the night the records learned the question

Raw session record. Immutable (wiki schema §1). Continues
`2026-08-13-the-map-becomes-a-workspace.md` — the same session, from the point where the owner
stopped browsing the vault and asked for the rebuilt server to be verified end to end.

Eleven PRs merged. **Not one of the defects below was found by reading code.** Each came from an
outside reference: the host's own git, CI's uploaded artifacts, a `find` over the record
repository, the owner looking at a rendered page, and — twice — a measurement that refuted
something I had asserted an hour earlier.

---

## The e2e sweep, and the two things it found

The server half passed: 1619, Asia/Seoul, six MCP tools, `subscription: live`, `session: alive`
on a cookie nine days old, and the extension proven alive not by a log but by `timers.json`
advancing at 15:16:01 (page load) and 15:16:32 (the 30-second heartbeat) after six quiet minutes.

**One of my own conclusions was wrong mid-sweep.** I read an empty server log as "the extension
never reached the server". A successful `/watch` writes no log line at all — deliberately, since
it fires every thirty seconds forever. The observation point had to move to the file, not the log.

Then two defects:

**The options page sent the reader to the one directory the token must not be in** (#268).
`extension/options.html` said `.ps/watch-token` was "in your record repository". Measured:
`<records>/.ps/` holds `raw/`, `backup.json`, `git-credentials` and `timers.json` — and no token.
Worse than a dead end: the reader finds a *different* credential there and learns that credentials
belong inside the repository they push. The identical sentence had been fixed in
`extension/README.md` three days earlier; the document *about* the extension was corrected and the
extension's own UI was not. Second time in one day that file was invisible to a sweep — the
8080→1619 pass missed it for an `--include` list without `.html`.

**The push credential pointer was written into the repository the host shares** (#269). #258 ran
`git config credential.helper "store --file=/records/.ps/git-credentials"` inside the record
repository. `/records` is a container path; that `.git/config` is the host's working copy. On the
host:

```
$ git -C ~/Desktop/ps-records push --dry-run origin main
fatal: unable to get credential storage lock in 1000 ms: No such file or directory
To https://github.com/BrokenFinger98/ps-records.git
   6e0b3ff..5e58a35  main -> main
```

The push **succeeded** — `osxkeychain` answers first — and printed `fatal:` anyway. A status that
disagrees with what happened, with the sign flipped from #167: there a green badge covered a lost
submission, here a failure message covered a working push.

The fix passes the pointer per invocation (`git -c`) and persists it nowhere, so host, container
and native stop being three cases. **The near miss was the container's global config** — it keeps
a bare `git push` working inside the container, and on `./gradlew bootRun` it writes the
*developer's* `~/.gitconfig`. Same trespass, one directory over, and worse for being invisible.

---

## The coverage gate measured an eighth of what it named

The owner asked why the coverage CI job enforces nothing. A threshold did exist —
`verifyCalculatorCoverage`, 95% branch on `domain/calc`, passing at 96%. It read the package by
exact-name regex, and Kover emits `domain/calc/runner` as a **separate** `<package>`: 132 branches
measured, **862 ignored**, 287 of them uncovered — the largest such pool in the repository, inside
the one place we told ourselves was held to 95%.

The function's own comment argued for the shape that caused it: *"Reads the package's own counter
rather than summing classes, so a class added later is included without touching this."* True of
classes, false of sub-packages.

### A global threshold was measured and refused

Branch read 75% overall and the largest contributor was not missing tests.

**⚠️ My first diagnosis was wrong.** I attributed `SubmissionRecord`'s 70 uncovered branches to
its generated `equals`/`hashCode`/`copy`. Method-level counters:

```
## SubmissionRecord  (missed 70)
      69  <init>
       1  isCodeAttached
```

They are in the **constructor**. Kotlin compiles each default parameter value into a bitmask test,
and that class has exactly 15 defaulted fields. `adapter/catalog`'s 23% was the same thing —
45 of its 52 missed branches were `@Serializable` data-class constructors. Same conclusion (nobody
should test them), different mechanism, and the mechanism is what decides how to filter it. I had
inferred rather than measured, and it was in the issue body before it was corrected.

Set generated members aside and exclude the runners: **85% over 1,733 branches**, which is what
made a per-package floor workable at all.

### Tests written rather than floors lowered

`adapter/catalog` 23→80 · `adapter/cable` 77→86 · `adapter/git` 78→81 · `adapter/config` 36→65.
Every one turned out to be an absence path this project claims to care about: a lesson the catalog
does not have, a channel nobody subscribed to, a throwable with no message, a GitHub answer that is
neither *created* nor *already exists*, an unstamped build.

One production change fell out: `ClasspathProblemCatalog.resource` chained safe calls onto
`bufferedReader()` and `use`, neither of which can return null, carrying two branches **no test
could ever reach**. An unreachable branch inside a coverage floor is a number nobody can move
honestly.

### And the gate hid the one number it promised to watch

Exempt packages were filtered out *before* the report, so `domain/calc/runner` — whose exemption
said "raising it is tracked separately" — was the only package nobody could track. Fixed hours
later (#283).

**⚠️ The visible number immediately refuted the hypothesis that asked for it.** I expected CI to
read higher than the local 66%, because `CsharpRunnerExecutionTest` skips all 8 of its tests here
(this Mac's dotnet is an x86_64 install on arm64 and cannot start — the probe reports it as absent,
exactly as its own comment says it would). I downloaded CI's uploaded test results instead of
reasoning about them:

```
CRunnerExecutionTest 9 · CppRunnerExecutionTest 7 · CsharpRunnerExecutionTest 8
JavaRunnerExecutionTest 7 · JavascriptRunnerExecutionTest 6 · KotlinRunnerExecutionTest 6
PythonRunnerExecutionTest 6      →  49 tests, 0 skipped
```

And `domain/calc/runner` reads **575/862 on both**, to the branch.

**⚠️ Which also killed a recommendation I had made an hour earlier.** I had proposed "the runner
execution proof" as the next piece of work, on the premise that the runners had no execution
tests. They have had them since #84/#86 — seven classes compiling and running generated code for
real, with the exact `assumeTrue` posture I was about to suggest inventing. They add **zero**
branch coverage, because the `*RunnerTest` siblings already cover the generation branches and an
execution test proves the output *correct* without visiting a new path. So the 287 are generation
paths nobody has written a case for, and closing the exemption means more generation tests. The
ADR was corrected (#285); it had been sending the next reader after work already done.

---

## The records knew how you failed and not what was asked

Six MCP tools returned metadata and gradings and none could say what the problem asked.

The owner raised it with BaekjoonHub. **⚠️ My first objection was wrong and I withdrew it**: I
argued Baekjoon's statements have mixed provenance while Programmers' are Grepp's own, so the
precedent would not transfer. The owner produced a *Programmers* example — lesson 276036, full
statement, in a **public** repository, years old. Same rights holder, same practice, public where
ours is private. What survives is narrower: no complaint is evidence about risk, not about rights.

Three properties, each the reason for a different part of the shape:

- **It rides on a fetch already made.** `CodeAttachment` downloads the problem page for the saved
  code; the statement is on that same page, so `CodeFetch.Fetched` carries both and Programmers
  sees no additional request.
- **A file of its own, written once.** `README.md` is regenerated on every grading, so a statement
  inside it would need re-fetching every time to survive — the trap that blocked design §6.9's
  retrospectives, one file over. `![[statement]]` keeps the reader's page single while the
  regeneration boundary stays clean.
- **jsoup, not regex.** The parsers beside it read one attribute off one tag, which regex does
  honestly. Programmers renders the author's Markdown into HTML and leaves `class="markdown"`
  saying so, which is what makes converting it back defined rather than guessed. Measured across
  five lessons: twenty tags, **no nested `<div>`**, which is what makes the boundary unambiguous.

**The defect appeared only against the real pages.** Lesson 17676 wraps every worked example in a
`<p>` inside an `<li>`, which the keep-what-you-do-not-know fallback printed into the note as raw
markup. The scrubbed fixture — structurally verbatim, every word invented — could not have caught
it, because I wrote the fixture.

### Storing it was half the job

`get_problem` did not return it, so the sentence that justified the whole feature stayed exactly
as true as it had been (#279).

### And "다 한거야?" was the right question

The owner asked whether the statement work was finished. Measured rather than remembered:

```
statement.md files:    0
problems with records: 5
codePending records:   0   (48 log lines → 24 after resolving corrections)
```

The statement is written while attaching a grading's code, and `attachPending` only revisits
pending records — of which there were none. Those five problems would have received a statement
**only by being solved again**, and a user adopting the tool after a year of solving keeps a year
without one. The same hole meant a first attachment that hit a rate limit never retried, ever.

The backfill (#281) takes its work list off disk — the log already carries `lessonId`, `title` and
`language`, and the layout says which have a file — so requests go out only for what is missing,
one per *problem* rather than per record. Courtesy is the shape: 20 per boot, a two-second pause,
and a stop at the first blocking answer, since an expired session is shared by every remaining
problem.

It runs **before** the vault refresh, which is what puts the embed on the page.

**Live, first try:**

```
Fetching the problem statement of 5 problem(s) recorded before it was kept
Problem statements backfilled: BackfillReport(filled=5, failed=0, blocked=false)
```

Five files, five READMEs carrying `![[statement]]`, committed, and `get_problem` returning 1,109
bytes of it. That was the first time fetch → parse → write ran together — the end-to-end proof
#277 was still owed, arriving without a new grading.

---

## The AI was told what the tools return and never how to read them

`docs/mcp.md` is careful about how these records mislead. **A human reads that document; the model
never opens it.** The MCP `instructions` string was one sentence.

It now carries navigation, the readings that have *actually* gone wrong (a run counted as an
attempt — #235 and #237 both; wall clock read as effort, 77,251 against 37 on one measured record;
absent read as zero; a conclusion drawn over `incompleteHistory`), what the data cannot speak about
at all (no cohort, so "slow" only means slow against this learner's own passes), and the part that
is the reader's — said *to* the model rather than about it.

**The test that pins the line was wrong first.** It blacklisted words — `weak`, `struggling`,
`should practise` — and failed immediately, because the text says *"it will not tell you which of
these numbers is a weakness."* Refusing to name a weakness requires the word. It bans **shapes**
now (`you are `, `the learner is`, `you tend`), which a refusal cannot contain by accident.

Live: 2,607 characters, under the 3,000 the test caps it at.

---

## A security policy that says what is *not* a vulnerability

Every piece existed across five files and none was the one GitHub shows (#289). `SECURITY.md`
links rather than restates — a security document that copies the code is one that will disagree
with it.

The section worth the most is *"What is not a vulnerability here"*: the `/watch` token is a
loopback gate rather than an authentication system, anything with local filesystem access has
already won, and the records repository is private by construction rather than by permission
checks. It also draws a boundary in public — **using a private protocol is a question of courtesy
and terms, not a security report** — because conflating them makes both conversations harder.

Measured while writing: `private-vulnerability-reporting` is disabled on the repository, so the
document says to use it *if offered* rather than pointing at a channel that is not open.

---

## What found each thing

| Found by | What it found |
|---|---|
| The host's own git | the credential pointer printing `fatal:` over a working push (#269) |
| A `grep` with no filters | the options page still naming the record repository (#268) |
| `find` over the record repository | 0 `statement.md` files, which "다 한거야?" was really asking (#280) |
| CI's uploaded test artifacts | 49 execution tests with 0 skipped, refuting my own hypothesis (#283/#285) |
| Method-level coverage counters | `<init>` bitmasks, not `equals`/`hashCode` (#272) |
| The owner reading a rendered page | a `Kind` column declared and shown by no view (#273) |
| The five real problem pages | `<p>` inside `<li>`, which my own fixture could not have caught (#277) |

Seven findings, seven outside references, and **zero** found by re-reading the code that produced
them. Three of the seven contradicted something I had stated in the preceding hour.
