# The `exam_prep` Prompt — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One slash command before a coding test asks the learner's own model to find their recurring mistakes in their repair steps, cite the records behind each, and turn each into problems to re-solve and drills. The server still counts and names nothing; the prompt is where the naming is asked for, in the open.

**Architecture:** Part 4.4 of `docs/superpowers/specs/2026-10-07-mistake-patterns-design.md`. The MCP adapter gains the **prompts** capability with one prompt, `exam_prep(language?, since?, part?)`. A pure `ExamPrepScope` checks the arguments and renders the scope; `ExamPrepPrompt` renders the text; `McpPromptCatalog` lists the prompt and answers `prompts/get`; `McpDispatcher` routes `prompts/list` and `prompts/get` in both protocol eras. No application or domain change, nothing stored, MCP stays read-only.

**Tech Stack:** Kotlin (JVM 25), Spring Boot 4, kotlinx.serialization, JUnit 5 + Kotest assertions, Gradle Kotlin DSL, ktlint (max line 120), Kover branch floors.

---

## Read before planning — and what it changed

From the MCP specification (`modelcontextprotocol/modelcontextprotocol` @ `0a11bf68c7`, 2026-10-06: `server/prompts` pages and `schema/<rev>/schema.ts` for `2025-06-18`, `2025-11-25`, `2026-07-28`) and from Claude Code 2.1.285 (docs, CHANGELOG, and the installed client's code — read, not run end-to-end):

- **Shapes.** The capability is `"prompts": {}` — in `initialize` for the handshake era and in `server/discover` for the modern one. `prompts/list` → `{ prompts: [{ name, title?, description?, arguments?: [{ name, description?, required? }] }] }`. `prompts/get(name, arguments: {string: string})` → `{ description?, messages: [{ role, content }] }`. An unknown prompt and a missing or invalid argument are `-32602` in all three revisions.
- **Modern additions.** `prompts/list` is a cacheable result. `ttlMs` and `cacheScope` are required, as for `tools/list`. Every result carries `resultType`. `prompts/get` must mirror its `name` into `Mcp-Name`, as `tools/call` does: `400` + `-32020` on disagreement, with the Base64 sentinel decoded first.
  → **Claude Code's modern codec rejects a `prompts/list` without `ttlMs`/`cacheScope` — it has no default — and then shows no prompts at all.** See D5.
- **Claude Code lists the prompt as `/programmers-tracker:exam_prep (MCP)`.** Only after the server declares the capability does it call `prompts/list`. It **splits the words after the command on whitespace, with no quoting, and maps them onto the arguments in the order the server lists them**; extra words are dropped from `arguments`, but the model still sees the raw line. It ignores `title` and argument descriptions. It merges every message into one injected user message, ignoring `role`. It applies no length cap to a prompt's description or text; the 2,048-character cut applies to instructions and tool descriptions only. The model cannot run a prompt itself — only the user can.
- **Measured on `catalog.json`: 38 of the 49 part names contain a space** ("GROUP BY", "코딩 기초 트레이닝", "String, Date", …). In Claude Code a `part` argument therefore arrives as its first word. See D3.
- **This machine's Claude Code connected to the tracker 27 times, all on the modern revision `2026-07-28`** (local MCP logs). The modern path is the one that matters in practice; the handshake path stays for other clients.

## Decisions made while planning

| # | Decision | Reason |
|---|---|---|
| D1 | One prompt, `exam_prep`, every argument optional; the answer is one `user` text message | Spec §4.4. Claude Code merges messages and ignores `role` anyway |
| D2 | **The argument order is an interface: `language`, `since`, `part`** | Claude Code fills arguments positionally. `language` is the one most sessions give; `part` goes last because its values have spaces |
| D3 | **`since` is checked strictly with the tools' own `Since` parser; `language` and `part` pass through unchecked — except a `language` that reads as a date, refused as a positional slip** | A bad `since` would prepare the session over the wrong range and look right, so it is refused before anything runs. A `part` check would refuse "GROUP", which is exactly what Claude Code sends for "GROUP BY". Instead the text tells the model three things:
<ul><li>match a given part against the part **keys** `stats(groupBy=part)` returns (a part bucket has no `label`);</li><li>call `repair_steps` with the full name, and say which part it used;</li><li>whenever something narrows, an empty answer is not an absence of mistakes, and it should name the argument that may not match.</li></ul>
`/exam_prep 2026-09-01` is the likely slip with positional arguments: it would narrow `repair_steps` to nothing in silence. The refusals a positional slip causes — a bad `since`, a language that reads as a date, an unknown argument — name the order: language, since, part |
| D4 | Unknown prompt, unknown argument, or a non-string argument → `-32602`, carried on `400` modern / `200` handshake. A blank argument is not given | The spec's code. Same carriage as an unknown tool (`McpToolInvoker`). A form-style client sends an empty field as `""` |
| D5 | Modern `prompts/list` goes through `cacheable(...)` (`ttlMs`, `cacheScope: private`) | Required by the revision, and by Claude Code's codec |
| D6 | `prompts/get` gets the `Mcp-Name` check; `McpCall.toolName()` becomes `name()` | The binding requires the header for `prompts/get`. The accessor reads `params.name` for both methods, so the old name would mislead |
| D7 | No `listChanged`, no completions | The prompt set is fixed at compile time; Claude Code never asks for `ref/prompt` completions |
| D8 | The prompt carries its own readings; no length-budget test on it | The instructions have 27 characters left (#353). Claude Code does not cut prompt text. The text stays short because it is pasted into a conversation each time it runs |

## File structure

- Create `src/main/kotlin/com/brokenfinger/tracker/adapter/mcp/ExamPrepScope.kt`: argument check (D3) and scope rendering. Pure.
- Create `src/main/kotlin/com/brokenfinger/tracker/adapter/mcp/ExamPrepPrompt.kt`: the text. Pure.
- Create `src/main/kotlin/com/brokenfinger/tracker/adapter/mcp/McpPromptCatalog.kt`: `prompts/list` definitions and the `prompts/get` answer, with D4's refusals.
- Modify `src/main/kotlin/com/brokenfinger/tracker/adapter/mcp/McpDispatcher.kt`: capability, routes in both eras, `Mcp-Name` for `prompts/get`.
- Modify `src/main/kotlin/com/brokenfinger/tracker/adapter/mcp/McpCall.kt`: rename `toolName()` → `name()`.
- Tests:
  - `src/test/kotlin/com/brokenfinger/tracker/adapter/mcp/ExamPrepScopeTest.kt`, `ExamPrepPromptTest.kt`, `McpPromptCatalogTest.kt` (new)
  - `McpDispatcherTest.kt`, `McpControllerTest.kt`, `McpCallTest.kt` (modified)
  - `src/test/kotlin/com/brokenfinger/tracker/support/fixtures/McpFixtures.kt`: `aPromptGetParams`, and `headersFor` reads `name()`
- Docs:
  - `docs/mcp.md` + `docs/mcp.ko.md`
  - `README.md` + `README.ko.md`
  - the spec's status line
  - ADR `docs/llm-wiki/wiki/decisions/2026-10-07-exam-prep-asks-in-the-open.md` + `docs/llm-wiki/index.md`
  - `.harness/state/progress.md`

---

### Task 1: `ExamPrepScope` — what one session covers

**Files:**
- Create: `src/main/kotlin/com/brokenfinger/tracker/adapter/mcp/ExamPrepScope.kt`
- Test: `src/test/kotlin/com/brokenfinger/tracker/adapter/mcp/ExamPrepScopeTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.domain.calc.Since
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** Zero mocks: a scope is three optional strings and the words they turn into. */
class ExamPrepScopeTest {
    @Test
    fun `no argument means everything on record and an unnarrowed call`() {
        val scope = ExamPrepScope()

        scope.paragraph() shouldBe "Scope: everything on record."
        scope.repairStepsCall() shouldBe "repair_steps()"
    }

    @Test
    fun `every argument narrows the repair_steps call in the order the tool documents them`() {
        val scope = ExamPrepScope(language = "java", since = "2026-09-01", part = "SELECT")

        scope.repairStepsCall() shouldBe "repair_steps(language=java, since=2026-09-01, part=SELECT)"
    }

    @Test
    fun `the paragraph names the scope and says only repair_steps takes it`() {
        val paragraph = ExamPrepScope(language = "mysql", since = "2026-09-01").paragraph()

        paragraph shouldContain "Scope: language mysql, since 2026-09-01."
        paragraph shouldContain "Only repair_steps takes this scope"
    }

    /** Claude Code splits arguments on spaces: "GROUP BY" arrives as "GROUP" (38 of 49 parts have a space). */
    @Test
    fun `a given part comes with the warning that it may be cut short`() {
        val paragraph = ExamPrepScope(part = "GROUP").paragraph()

        paragraph shouldContain "part \"GROUP\""
        paragraph shouldContain "split arguments on spaces"
        paragraph shouldContain "say which part you used"
    }

    @Test
    fun `no part, no warning about parts`() {
        ExamPrepScope(language = "java").paragraph() shouldNotContain "split arguments on spaces"
    }

    @Test
    fun `a since the tools would refuse is refused here, in their words`() {
        val refused = shouldThrow<IllegalArgumentException> { ExamPrepScope(since = "yesterday") }

        refused.message shouldBe Since.FORMAT
    }

    @Test
    fun `an offset date-time is a since the tools take, so it is taken`() {
        val call = ExamPrepScope(since = "2026-09-01T09:00:00+09:00").repairStepsCall()

        call shouldContain "since=2026-09-01T09:00:00+09:00"
    }

    /** A part is matched by the tools, not here: checking it would refuse what Claude Code sends. */
    @Test
    fun `language and part are taken as typed, whatever they say`() {
        val scope = ExamPrepScope(language = "fortran", part = "코딩")

        scope.repairStepsCall() shouldBe "repair_steps(language=fortran, part=코딩)"
    }

    /** `/exam_prep 2026-09-01` puts the date where `language` goes; refused, it says why. */
    @Test
    fun `a language that reads as a date is refused as a positional slip`() {
        val refused = shouldThrow<IllegalArgumentException> { ExamPrepScope(language = "2026-09-01") }

        refused.message shouldContain "positional — language, since, part"
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./gradlew test --tests 'com.brokenfinger.tracker.adapter.mcp.ExamPrepScopeTest'`
Expected: compilation FAILS — `Unresolved reference 'ExamPrepScope'`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.domain.calc.Since

/**
 * What one `exam_prep` session covers (spec `2026-10-07-mistake-patterns-design` §4.4). Every field is
 * optional, and an absent one narrows nothing.
 *
 * [since] is checked, with the tools' own parser and in their words. A session prepared over the wrong
 * range would look right, so a bound that does not parse is refused before anything runs.
 *
 * [language] and [part] are taken as typed. The tools match them, and an unmatched one answers empty.
 * The one exception is a language that reads as a date, which is refused, because with positional
 * arguments that is the likely slip. [part] above all must not be checked: Claude Code splits prompt
 * arguments on spaces, so "GROUP BY" arrives as "GROUP", and 38 of the catalog's 49 part names have
 * a space. Instead of a check here refusing it, the paragraph tells the model to match a given part
 * against the labels `stats` returns ([[decisions/2026-10-07-exam-prep-asks-in-the-open]]).
 */
data class ExamPrepScope(val language: String? = null, val since: String? = null, val part: String? = null) {
    init {
        if (since != null) Since.from(since)
        require(language == null || !readsAsDate(language)) { positional(language) }
    }

    /** What the session covers, said the way the prompt opens. */
    fun paragraph(): String {
        if (narrowings().isEmpty()) return "Scope: everything on record."
        val lines = listOf("Scope: ${narrowings().joinToString(", ")}.", ONLY_REPAIR_STEPS)
        return (lines + listOfNotNull(part?.let { partWarning(it) })).joinToString("\n")
    }

    /** The `repair_steps` call that covers this scope, spelled as a call. */
    fun repairStepsCall(): String = "repair_steps(${arguments().joinToString(", ")})"

    private fun narrowings(): List<String> =
        listOfNotNull(language?.let { "language $it" }, since?.let { "since $it" }, part?.let { "part \"$it\"" })

    private fun arguments(): List<String> =
        listOfNotNull(language?.let { "language=$it" }, since?.let { "since=$it" }, part?.let { "part=$it" })

    private fun partWarning(given: String): String =
        "Some clients split arguments on spaces, so \"$given\" may be the start of a longer part name " +
            "(\"GROUP\" for \"GROUP BY\"). Match it against the part labels stats returns before narrowing " +
            "by it, and say which part you used."

    // The one positional slip worth catching: skip `language`, type the date first, and it lands here —
    // where it would narrow repair_steps to nothing instead of being refused.
    private fun readsAsDate(text: String): Boolean = runCatching { Since.from(text) }.isSuccess

    private fun positional(given: String?): String =
        "language \"$given\" reads as a date; the arguments are positional — language, since, part"

    private companion object {
        const val ONLY_REPAIR_STEPS =
            "Only repair_steps takes this scope; stats and list_problems answer over everything on record."
    }
}
```

- [ ] **Step 4: Run the test and confirm it passes**

Run: `./gradlew test --tests 'com.brokenfinger.tracker.adapter.mcp.ExamPrepScopeTest'`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/brokenfinger/tracker/adapter/mcp/ExamPrepScope.kt src/test/kotlin/com/brokenfinger/tracker/adapter/mcp/ExamPrepScopeTest.kt
git commit -m "feat(mcp): the scope an exam_prep session covers

Refs #364"
```

### Task 2: `ExamPrepPrompt` — the text

**Files:**
- Create: `src/main/kotlin/com/brokenfinger/tracker/adapter/mcp/ExamPrepPrompt.kt`
- Test: `src/test/kotlin/com/brokenfinger/tracker/adapter/mcp/ExamPrepPromptTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.brokenfinger.tracker.adapter.mcp

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * The only text in which this server asks for interpretation. These pin what it asks for and the
 * readings it must carry, because the server instructions have no room left for them (#353).
 */
class ExamPrepPromptTest {
    private val text = ExamPrepPrompt.text(ExamPrepScope())

    @Test
    fun `it asks for the naming in the open and for the records behind it`() {
        text shouldContain "The tools count and name nothing. Here the naming is asked for"
        text shouldContain "cite the record ids behind it"
        text shouldContain "say how many problems it spans"
        text shouldContain "A pattern seen once is not a pattern."
    }

    @Test
    fun `it walks the five steps of the spec`() {
        text shouldContain "1. Call stats with groupBy=part, then with groupBy=level."
        text shouldContain "2. Call repair_steps()."
        text shouldContain "list_problems(status=untouched, part=<that pattern's part>)"
        text shouldContain "two or three short drills aimed at exactly that point"
        text shouldContain "5. Last, what the records could not support."
    }

    @Test
    fun `it carries the readings a pre-exam session gets wrong`() {
        text shouldContain "A step shows what changed, not what was wrong."
        text shouldContain "A run is not an attempt"
        text shouldContain "Absent is not zero"
        text shouldContain "Run code is kept by tracker versions from 2026-10-07 on"
        text shouldContain "`codeLate: true`"
        text shouldContain "`incompleteHistory`"
    }

    @Test
    fun `it reads the truncation the tool reports instead of trusting the first page`() {
        text shouldContain "When an answer says `truncated`, call again with `limit` set to its `total`"
    }

    /** A renamed tool would leave the prompt sending the model to one that does not exist. */
    @Test
    fun `every tool it sends the model to exists`() {
        listOf("stats", "repair_steps", "list_problems").forEach { tool ->
            McpToolCatalog.NAMES shouldContain tool
            text shouldContain tool
        }
    }

    @Test
    fun `the scope it is given narrows the repair_steps call it asks for`() {
        val scoped = ExamPrepPrompt.text(ExamPrepScope(language = "java", since = "2026-09-01"))

        scoped shouldContain "Scope: language java, since 2026-09-01."
        scoped shouldContain "2. Call repair_steps(language=java, since=2026-09-01)."
        scoped shouldNotContain "Scope: everything on record."
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./gradlew test --tests 'com.brokenfinger.tracker.adapter.mcp.ExamPrepPromptTest'`
Expected: compilation FAILS — `Unresolved reference 'ExamPrepPrompt'`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.brokenfinger.tracker.adapter.mcp

/**
 * The text `exam_prep` hands the client's model (spec `2026-10-07-mistake-patterns-design` §4.4).
 *
 * The one place this server asks for interpretation, and it asks in the open. The tools still count and
 * name nothing ([[decisions/2026-08-12-the-server-counts-and-names-nothing]]). This is the text the
 * learner sends their own model through a slash command, asking it to do the naming.
 *
 * **It carries its own readings.** The server instructions are at 1,973 of the 2,000 characters a client
 * receives whole, so what a pre-exam session must not misread travels here (#353). Claude Code cuts
 * instructions and tool descriptions at 2,048 characters but not a prompt's text (2.1.285, read from the
 * client), so this has no budget test. It stays short because it is pasted into a conversation every
 * time it runs.
 */
object ExamPrepPrompt {
    const val NAME = "exam_prep"

    fun text(scope: ExamPrepScope): String =
        listOf(OPENING, scope.paragraph(), ASKED, steps(scope), READINGS).joinToString("\n\n")

    private fun steps(scope: ExamPrepScope): String =
        listOf(STATS_STEP, "2. Call ${scope.repairStepsCall()}. $REPAIR_STEP", LATER_STEPS).joinToString("\n")

    private const val OPENING =
        "Prepare me for a coding test from my own Programmers records, using this server's tools."

    private const val ASKED = "The tools count and name nothing. Here the naming is asked for: find my " +
        "recurring mistakes, and show the records behind each one."

    private const val STATS_STEP = "1. Call stats with groupBy=part, then with groupBy=level. Note where " +
        "passing took the most runs and submits."

    private const val REPAIR_STEP = "When an answer says `truncated`, call again with `limit` set to its " +
        "`total`; if that is too many to read, narrow with `since` and say so. Group the steps into recurring " +
        "patterns — an argument order, a method name, an off-by-one bound, a syntax slip. Name each pattern by " +
        "what its diffs show, cite the record ids behind it, and say how many problems it spans. A pattern " +
        "seen once is not a pattern."

    private val LATER_STEPS = listOf(
        "3. For each pattern: the problems to re-solve (its steps' lessonId and title), and up to three " +
            "problems from list_problems(status=untouched, part=<that pattern's part>).",
        "4. For each pattern: two or three short drills aimed at exactly that point — if the diffs keep " +
            "fixing substring bounds, \"take the 3rd–4th characters with substring\".",
        "5. Last, what the records could not support.",
    ).joinToString("\n")

    private val READINGS = listOf(
        "Readings that are easy to get wrong:",
        "- A step shows what changed, not what was wrong. Where the diff alone cannot tell, say so.",
        "- A run is not an attempt; stats counts submits only.",
        "- Absent is not zero: a step without `diff` says why in `noDiff`.",
        "- Run code is kept by tracker versions from 2026-10-07 on, so an earlier step that starts or ends " +
            "at a run has no diff. Submit code was always kept.",
        "- `codeLate: true` marks code that may belong to the next grading; build no pattern on it alone.",
        "- `incompleteHistory` means gradings were captured that no record represents: say the counts have " +
            "holes.",
    ).joinToString("\n")
}
```

> Note for the implementer: `private const val` and `private val` declared after `text()` are fine inside an `object`. But `LATER_STEPS`/`READINGS` are initialized in declaration order, and `text()` is only called after the object is initialized — keep them as `val`s in the object body, not in a companion.

- [ ] **Step 4: Run the test and confirm it passes**

Run: `./gradlew test --tests 'com.brokenfinger.tracker.adapter.mcp.ExamPrepPromptTest'`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/brokenfinger/tracker/adapter/mcp/ExamPrepPrompt.kt src/test/kotlin/com/brokenfinger/tracker/adapter/mcp/ExamPrepPromptTest.kt
git commit -m "feat(mcp): the exam_prep text — the naming asked for in the open

Refs #364"
```

### Task 3: `McpPromptCatalog` — the list and the answer

**Files:**
- Create: `src/main/kotlin/com/brokenfinger/tracker/adapter/mcp/McpPromptCatalog.kt`
- Test: `src/test/kotlin/com/brokenfinger/tracker/adapter/mcp/McpPromptCatalogTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.domain.calc.Since
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

class McpPromptCatalogTest {
    @Test
    fun `it lists one prompt, exam_prep`() {
        McpPromptCatalog.definitions().map { it.jsonObject["name"]!!.jsonPrimitive.content }
            .shouldContainExactly(ExamPrepPrompt.NAME)
    }

    /** Claude Code fills arguments by position, split on spaces — the order is an interface (D2). */
    @Test
    fun `its arguments come in the order Claude Code fills them, none required`() {
        val arguments = McpPromptCatalog.definitions().single().jsonObject["arguments"]!!.jsonArray

        val names = arguments.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        names.shouldContainExactly("language", "since", "part")
        arguments.forEach { it.jsonObject["required"]!!.jsonPrimitive.boolean shouldBe false }
    }

    @Test
    fun `get answers one user message carrying the text`() {
        val answer = McpPromptCatalog.get(ExamPrepPrompt.NAME, JsonObject(emptyMap()))

        val message = answer["messages"]!!.jsonArray.single().jsonObject
        message["role"]!!.jsonPrimitive.content shouldBe "user"
        message["content"]!!.jsonObject["type"]!!.jsonPrimitive.content shouldBe "text"
        message["content"]!!.jsonObject["text"]!!.jsonPrimitive.content shouldBe ExamPrepPrompt.text(ExamPrepScope())
    }

    @Test
    fun `the arguments given reach the text`() {
        val arguments = buildJsonObject {
            put("language", "java")
            put("since", "2026-09-01")
        }

        val text = textOf(McpPromptCatalog.get(ExamPrepPrompt.NAME, arguments))

        text shouldContain "repair_steps(language=\"java\", since=\"2026-09-01\")"
    }

    /** Read as given, then trimmed — the tools' own parser trims too, so the call names the date itself. */
    @Test
    fun `a since padded with spaces is read as the date it holds`() {
        val text = textOf(McpPromptCatalog.get(ExamPrepPrompt.NAME, buildJsonObject { put("since", " 2026-09-01 ") }))

        text shouldContain "since=\"2026-09-01\""
    }

    /** A form-style client sends an empty field as "" — that is "not given", not a value. */
    @Test
    fun `a blank or null argument is not given`() {
        val arguments = buildJsonObject {
            put("language", "  ")
            put("since", JsonNull)
        }

        textOf(McpPromptCatalog.get(ExamPrepPrompt.NAME, arguments)) shouldContain "Scope: everything on record."
    }

    @Test
    fun `an unknown prompt is refused as invalid params, naming the one there is`() {
        val refused = shouldThrow<McpFailure> { McpPromptCatalog.get("warmup_plan", JsonObject(emptyMap())) }

        refused.code shouldBe McpErrors.INVALID_PARAMS
        refused.status shouldBe 400
        refused.message shouldContain ExamPrepPrompt.NAME
    }

    @Test
    fun `a missing name is refused the same way`() {
        val refused = shouldThrow<McpFailure> { McpPromptCatalog.get(null, JsonObject(emptyMap())) }

        refused.code shouldBe McpErrors.INVALID_PARAMS
    }

    @Test
    fun `an unknown argument is refused by name`() {
        val refused = shouldThrow<McpFailure> {
            McpPromptCatalog.get(ExamPrepPrompt.NAME, buildJsonObject { put("level", "2") })
        }

        refused.code shouldBe McpErrors.INVALID_PARAMS
        refused.message shouldContain "level"
    }

    @Test
    fun `a since that does not parse is refused in the tools' words`() {
        val refused = shouldThrow<McpFailure> {
            McpPromptCatalog.get(ExamPrepPrompt.NAME, buildJsonObject { put("since", "yesterday") })
        }

        refused.code shouldBe McpErrors.INVALID_PARAMS
        refused.message shouldStartWith Since.FORMAT
        refused.message shouldContain "positional"
    }

    /** Prompt arguments are strings by the specification; a number is a mistake to name, in the tools' words. */
    @Test
    fun `an argument that is not a string is refused under its name`() {
        val refused = shouldThrow<McpFailure> {
            McpPromptCatalog.get(ExamPrepPrompt.NAME, buildJsonObject { put("language", 5) })
        }

        refused.message shouldBe "language must be text"
    }

    private fun textOf(answer: JsonObject): String =
        answer["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonObject["text"]!!.jsonPrimitive.content
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./gradlew test --tests 'com.brokenfinger.tracker.adapter.mcp.McpPromptCatalogTest'`
Expected: compilation FAILS — `Unresolved reference 'McpPromptCatalog'`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.domain.calc.Since
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The prompts this server offers — one, [ExamPrepPrompt].
 *
 * **The argument order is an interface.** Claude Code maps the words after a slash command onto these
 * arguments in the order listed, split on spaces and without quoting (2.1.285, read from the client).
 * So `language` comes first, the one most sessions give, and `part` last, because most part names
 * contain a space and arrive cut short ([[decisions/2026-10-07-exam-prep-asks-in-the-open]]).
 *
 * A refusal is `-32602`, the code the specification gives an unknown prompt and a bad argument. The
 * dispatcher carries it on `400` to a modern client and on `200` to a handshake one, as it does an
 * unknown tool.
 */
object McpPromptCatalog {
    val NAMES = listOf(ExamPrepPrompt.NAME)

    /** In the order Claude Code fills them, each with what it narrows. */
    private val EXAM_PREP_ARGUMENTS = listOf(
        "language" to "A Programmers language id (java, python3, mysql, …). Narrows repair_steps.",
        "since" to "${Since.FORMAT}. Narrows repair_steps to corrections recorded from then on.",
        "part" to "A part name. Listed last, because some clients split arguments on spaces.",
    )

    fun definitions(): JsonArray = buildJsonArray { add(examPrep()) }

    /** The `prompts/get` answer, or a refusal already shaped for the client. */
    fun get(name: String?, arguments: JsonObject): JsonObject {
        if (name != ExamPrepPrompt.NAME) throw refused("unknown prompt; this server offers ${NAMES.joinToString()}")
        return answer(ExamPrepPrompt.text(scopeOf(arguments)))
    }

    private fun scopeOf(arguments: JsonObject): ExamPrepScope {
        val unknown = arguments.keys - EXAM_PREP_ARGUMENTS.map { it.first }.toSet()
        if (unknown.isNotEmpty()) throw refused("unknown argument(s): ${unknown.sorted().joinToString()}")
        return try {
            scopeFrom(arguments)
        } catch (invalid: IllegalArgumentException) {
            throw refused(invalid.message ?: "the arguments could not be used")
        }
    }

    // Named, because three String? in a row would compile in any order.
    private fun scopeFrom(arguments: JsonObject): ExamPrepScope = ExamPrepScope(
        language = arguments.given("language"),
        since = arguments.given("since"),
        part = arguments.given("part"),
    )

    // Strings, by the specification, refused in the tools' words otherwise. A blank one is not given —
    // where a tool refuses a blank — because a client that shows arguments as a form sends an empty
    // field as "".
    private fun JsonObject.given(name: String): String? {
        val value = this[name]
        if (value == null || value is JsonNull) return null
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("$name must be text")
        return text.trim().takeIf { it.isNotEmpty() }
    }

    private fun answer(text: String): JsonObject = buildJsonObject {
        put("description", DESCRIPTION)
        put("messages", JsonArray(listOf(userText(text))))
    }

    // Claude Code merges every message into one and ignores `role`, so one user message is the whole answer.
    private fun userText(text: String): JsonObject = buildJsonObject {
        put("role", "user")
        putJsonObject("content") {
            put("type", "text")
            put("text", text)
        }
    }

    private fun examPrep(): JsonObject = buildJsonObject {
        put("name", ExamPrepPrompt.NAME)
        put("title", "Exam prep from your own repair steps")
        put("description", DESCRIPTION)
        put("arguments", JsonArray(EXAM_PREP_ARGUMENTS.map { (name, description) -> argument(name, description) }))
    }

    private fun argument(name: String, description: String): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("required", false)
    }

    private fun refused(message: String) = McpFailure(McpErrors.INVALID_PARAMS, 400, message)

    private const val DESCRIPTION = "Before a coding test: your recurring mistakes, found by your own model in " +
        "your repair steps and cited by record id, with problems to re-solve, untouched ones in the same parts, " +
        "and drills aimed at each."
}
```


- [ ] **Step 4: Run the test and confirm it passes**

Run: `./gradlew test --tests 'com.brokenfinger.tracker.adapter.mcp.McpPromptCatalogTest'`
Expected: PASS (11 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/brokenfinger/tracker/adapter/mcp/McpPromptCatalog.kt src/test/kotlin/com/brokenfinger/tracker/adapter/mcp/McpPromptCatalogTest.kt
git commit -m "feat(mcp): list exam_prep and answer prompts/get, refusing as the spec asks

Refs #364"
```

### Task 4: Route prompts in both eras

**Files:**
- Modify: `src/main/kotlin/com/brokenfinger/tracker/adapter/mcp/McpCall.kt` (`toolName()` → `name()`)
- Modify: `src/main/kotlin/com/brokenfinger/tracker/adapter/mcp/McpDispatcher.kt`
- Modify: `src/test/kotlin/com/brokenfinger/tracker/support/fixtures/McpFixtures.kt`
- Test: `McpDispatcherTest.kt`, `McpControllerTest.kt`, `McpCallTest.kt`

- [ ] **Step 0: One word in Task 3's catalog (review of Task 3)**

In `McpPromptCatalog.kt`, the `given` comment says the tools trim "when they match". `Since.from` trims when it parses, so make it "when they parse or match".

- [ ] **Step 1: Rename the accessor (no behaviour change)**

In `McpCall.kt`:

```kotlin
    /** `params.name` — the tool a `tools/call` runs, or the prompt a `prompts/get` renders. */
    fun name(): String? = (params["name"] as? JsonPrimitive)?.contentOrNull
```

Replace every `toolName()` with `name()`:
- `McpDispatcher.kt` (3 places)
- `McpCallTest.kt` (2 places)
- `McpFixtures.headersFor`: `McpHeaders(call.declaredVersion, call.method, call.name())`

Run: `./gradlew test --tests 'com.brokenfinger.tracker.adapter.mcp.*'` — expected PASS, unchanged counts.

- [ ] **Step 1b: A prompt's `arguments` must be an object (review of Task 3, Minor 1)**

`McpCall.arguments()` reads anything that is not an object as `{}`. Every `exam_prep` argument is optional, so `{}` is a whole request: `"arguments": "java"` would quietly widen the session to everything on record, the failure D3 refuses for `since`. Add a strict accessor and use it on both `PROMPTS_GET` routes. Five of the seven tools have the same widening; that is a follow-up, not this task.

Test first, in `McpCallTest`:

```kotlin
    /** `arguments` is optional in the specification: absent and null are none, not a refusal. */
    @Test
    fun `prompt arguments are none when absent or null, and the object when given`() {
        val absent = buildJsonObject { put("name", "exam_prep") }
        val nulled = JsonObject(absent + ("arguments" to JsonNull))
        val given = aToolCallParams("exam_prep", buildJsonObject { put("language", "java") })

        McpCall.from(aLegacyBody("prompts/get", absent)).promptArguments() shouldBe JsonObject(emptyMap())
        McpCall.from(aLegacyBody("prompts/get", nulled)).promptArguments() shouldBe JsonObject(emptyMap())
        McpCall.from(aLegacyBody("prompts/get", given)).promptArguments().keys shouldBe setOf("language")
    }

    /** A tool reads a malformed `arguments` as none; for a prompt that would widen to everything on record. */
    @Test
    fun `prompt arguments that are not an object are refused as invalid params`() {
        val params = buildJsonObject {
            put("name", "exam_prep")
            put("arguments", "java")
        }

        val refused = shouldThrow<McpFailure> { McpCall.from(aLegacyBody("prompts/get", params)).promptArguments() }

        refused.code shouldBe McpErrors.INVALID_PARAMS
    }
```

Imports needed in `McpCallTest`: `kotlinx.serialization.json.JsonNull`, `kotlinx.serialization.json.JsonObject` (and `aToolCallParams`, `aLegacyBody`, `shouldThrow` if not already there).

Run `./gradlew test --tests 'com.brokenfinger.tracker.adapter.mcp.McpCallTest'` — expected: compilation FAILS, `Unresolved reference 'promptArguments'`.

Then in `McpCall.kt`:

```kotlin
    /**
     * A prompt's arguments: the object given, none when absent or null, and a refusal for anything else.
     * Stricter than [arguments]: every argument here is optional, so `{}` is a whole request, and a
     * malformed `arguments` read as `{}` would prepare a session over everything on record and look right.
     */
    fun promptArguments(): JsonObject {
        val given = params["arguments"]
        if (given == null || given is JsonNull) return JsonObject(emptyMap())
        return given as? JsonObject
            ?: throw McpFailure(McpErrors.INVALID_PARAMS, 400, "arguments must be an object of strings")
    }
```

Both `PROMPTS_GET` branches in Step 5 call `McpPromptCatalog.get(call.name(), call.promptArguments())`.

Adjust the comment above the readers ("Every reader below casts instead of coercing … come back as an absent value") so it names `promptArguments()` as the one reader that refuses instead.

Correct `McpFailure`'s KDoc while in the file. It says the message "never [says] what the caller presented", but prompt refusals name the argument a user typed, quoted. The rule's reason is credentials, so say exactly that: the message never echoes a credential or a header value.

- [ ] **Step 2: Add the fixture**

In `McpFixtures.kt`, under `aToolCallParams`:

```kotlin
fun aPromptGetParams(name: String = "exam_prep", arguments: JsonObject = JsonObject(emptyMap())): JsonObject =
    aToolCallParams(name, arguments)
```

- [ ] **Step 3: Write the failing dispatcher tests**

Add to `McpDispatcherTest` (import `aPromptGetParams`):

```kotlin
    // ---------------------------------------------------------------- prompts, handshake era

    @Test
    fun `initialize declares prompts beside tools`() {
        val result = resultOf(dispatcher.dispatch(aLegacyCall("initialize", anInitializeParams()), McpHeaders()))

        result["capabilities"]!!.jsonObject.keys.shouldContainExactlyInAnyOrder("tools", "prompts")
    }

    @Test
    fun `prompts list answers the catalog, with none of the modern-only fields`() {
        val result = resultOf(dispatcher.dispatch(aLegacyCall("prompts/list"), McpHeaders()))

        result["prompts"]!!.jsonArray.size shouldBe McpPromptCatalog.NAMES.size
        result.shouldNotContainKey("resultType")
        result.shouldNotContainKey("ttlMs")
    }

    @Test
    fun `prompts get renders exam_prep`() {
        val result = resultOf(dispatcher.dispatch(aLegacyCall("prompts/get", aPromptGetParams()), McpHeaders()))

        result["messages"]!!.jsonArray.single().jsonObject["role"]!!.jsonPrimitive.content shouldBe "user"
    }

    @Test
    fun `an unknown prompt is refused on 200 as invalid params`() {
        val response = dispatcher.dispatch(aLegacyCall("prompts/get", aPromptGetParams("warmup_plan")), McpHeaders())

        response.status shouldBe 200
        errorOf(response)["code"]!!.jsonPrimitive.int shouldBe McpErrors.INVALID_PARAMS
    }

    // ---------------------------------------------------------------- prompts, modern era

    @Test
    fun `server discover declares prompts beside tools`() {
        val call = aModernCall("server/discover")

        val capabilities = resultOf(dispatcher.dispatch(call, headersFor(call)))["capabilities"]!!.jsonObject

        capabilities.keys.shouldContainExactlyInAnyOrder("tools", "prompts")
    }

    /** Claude Code's modern codec has no default for these, and without them it shows no prompt at all. */
    @Test
    fun `a modern prompts list is complete and carries the caching fields`() {
        val call = aModernCall("prompts/list")

        val result = resultOf(dispatcher.dispatch(call, headersFor(call)))

        result["resultType"]!!.jsonPrimitive.content shouldBe "complete"
        result["ttlMs"]!!.jsonPrimitive.content shouldBe McpProtocol.LIST_TTL_MS.toString()
        result["cacheScope"]!!.jsonPrimitive.content shouldBe "private"
        result["prompts"]!!.jsonArray.size shouldBe McpPromptCatalog.NAMES.size
    }

    @Test
    fun `a modern prompts get renders the prompt and is complete`() {
        val call = aModernCall("prompts/get", aPromptGetParams())

        val result = resultOf(dispatcher.dispatch(call, headersFor(call)))

        result["resultType"]!!.jsonPrimitive.content shouldBe "complete"
        result["messages"]!!.jsonArray.size shouldBe 1
    }

    @Test
    fun `an unknown modern prompt is a 400 carrying invalid params`() {
        val call = aModernCall("prompts/get", aPromptGetParams("warmup_plan"))

        val response = dispatcher.dispatch(call, headersFor(call))

        response.status shouldBe 400
        errorOf(response)["code"]!!.jsonPrimitive.int shouldBe McpErrors.INVALID_PARAMS
    }

    @Test
    fun `refuses a prompt get whose name header disagrees with its body or is missing`() {
        val call = aModernCall("prompts/get", aPromptGetParams())

        errorOf(dispatcher.dispatch(call, headersFor(call).copy(name = "stats")))["code"]!!
            .jsonPrimitive.int shouldBe McpErrors.HEADER_MISMATCH
        dispatcher.dispatch(call, headersFor(call).copy(name = null)).status shouldBe 400
    }

    @Test
    fun `prompt arguments that are not an object are refused, 400 modern and 200 handshake`() {
        val params = buildJsonObject {
            put("name", "exam_prep")
            put("arguments", "java")
        }
        val modern = aModernCall("prompts/get", params)

        dispatcher.dispatch(modern, headersFor(modern)).status shouldBe 400
        val legacy = dispatcher.dispatch(aLegacyCall("prompts/get", params), McpHeaders())
        legacy.status shouldBe 200
        errorOf(legacy)["code"]!!.jsonPrimitive.int shouldBe McpErrors.INVALID_PARAMS
    }

    @Test
    fun `accepts a prompt name a conservative client sent Base64-wrapped`() {
        val call = aModernCall("prompts/get", aPromptGetParams())
        val wrapped = "=?base64?" + java.util.Base64.getEncoder().encodeToString("exam_prep".toByteArray()) + "?="

        dispatcher.dispatch(call, headersFor(call).copy(name = wrapped)).status shouldBe 200
    }
```

And extend the existing `every modern result is tagged complete and identifies the server` to iterate `listOf("server/discover", "tools/list", "prompts/list")`.

- [ ] **Step 4: Run them and confirm they fail**

Run: `./gradlew test --tests 'com.brokenfinger.tracker.adapter.mcp.McpDispatcherTest'`
Expected: the new tests FAIL.
- The capability tests see only `tools`.
- `prompts/list` and `prompts/get` answer `-32601` (404 modern, 200 handshake).
- The name-header test sees `-32601` where it expects `-32020`: the header is not checked for `prompts/get` yet, so routing is what refuses.

Add `import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder` and `import com.brokenfinger.tracker.support.fixtures.aPromptGetParams`.

- [ ] **Step 5: Implement the routing**

In `McpDispatcher.kt`:

```kotlin
    private fun answerModern(call: McpCall): JsonObject = when (call.method) {
        DISCOVER -> discovery()
        TOOLS_LIST -> cacheable(toolList())
        TOOLS_CALL -> tools.call(call.name(), call.arguments())
        PROMPTS_LIST -> cacheable(promptList())
        PROMPTS_GET -> McpPromptCatalog.get(call.name(), call.promptArguments())
        else -> throw McpFailure(McpErrors.METHOD_NOT_FOUND, 404, "this server does not implement ${call.method}")
    }

    private fun legacy(call: McpCall): JsonObject = when (call.method) {
        INITIALIZE -> initialization(call)
        PING -> JsonObject(emptyMap())
        TOOLS_LIST -> toolList()
        TOOLS_CALL -> tools.call(call.name(), call.arguments())
        PROMPTS_LIST -> promptList()
        PROMPTS_GET -> McpPromptCatalog.get(call.name(), call.promptArguments())
        else -> throw McpFailure(McpErrors.METHOD_NOT_FOUND, 404, "this server does not implement ${call.method}")
    }

    private fun promptList(): JsonObject = buildJsonObject { put("prompts", McpPromptCatalog.definitions()) }

    // Fixed at compile time, so neither list ever changes under a client: no `listChanged`.
    private fun capabilities(): JsonObject = buildJsonObject {
        putJsonObject("tools") {}
        putJsonObject("prompts") {}
    }

    private fun verifyHeaders(call: McpCall, headers: McpHeaders) {
        mismatchUnless(headers.protocolVersion == call.declaredVersion, "MCP-Protocol-Version")
        mismatchUnless(headers.method == call.method, "Mcp-Method")
        if (call.method !in NAMED) return
        mismatchUnless(decoded(headers.name) == call.name(), "Mcp-Name")
    }
```

In the companion:

```kotlin
        const val PROMPTS_LIST = "prompts/list"
        const val PROMPTS_GET = "prompts/get"

        /** The methods whose `params.name` the modern binding mirrors into `Mcp-Name`. */
        val NAMED = setOf(TOOLS_CALL, PROMPTS_GET)
```

Update the `McpProtocol.LIST_TTL_MS` KDoc: "The tool and prompt sets are fixed at compile time…".

- [ ] **Step 6: Run the dispatcher tests and confirm they pass**

Run: `./gradlew test --tests 'com.brokenfinger.tracker.adapter.mcp.McpDispatcherTest'`
Expected: PASS. Then revert `NAMED` to `setOf(TOOLS_CALL)`. The name-header test must FAIL (the mismatch is accepted with 200). Restore.

- [ ] **Step 7: One end-to-end check through the controller**

In `McpControllerTest`, rename `postModern`'s `toolName` parameter to `name` (update its callers), then add:

```kotlin
    /** prompts/list → prompts/get, with the headers a modern client mirrors. */
    @Test
    fun `serves a modern client the exam_prep prompt`() {
        val listed = json(postModern("prompts/list", id = 1))["result"]!!.jsonObject
        listed["prompts"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content shouldBe "exam_prep"

        val got = json(postModern("prompts/get", aPromptGetParams(), name = "exam_prep", id = 2))["result"]!!.jsonObject
        got["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonObject["text"]!!.jsonPrimitive.content
            .shouldContain("repair_steps()")
    }
```

Run: `./gradlew test --tests 'com.brokenfinger.tracker.adapter.mcp.*'` — expected PASS.

- [ ] **Step 8: Gates and commit**

Run: `./scripts/check.sh && ./scripts/test.sh && ./scripts/build.sh` and the branch-coverage task, all exit 0.

```bash
git add -A src/main/kotlin/com/brokenfinger/tracker/adapter/mcp src/test/kotlin/com/brokenfinger/tracker/adapter/mcp src/test/kotlin/com/brokenfinger/tracker/support/fixtures/McpFixtures.kt
git commit -m "feat(mcp): serve prompts/list and prompts/get in both protocol eras

Declares the prompts capability in initialize and server/discover. The
modern prompts/list carries ttlMs and cacheScope, which the revision
requires (2026-07-28 server/utilities/caching) and Claude Code's codec
reads with no default. prompts/get is held to Mcp-Name like tools/call
(2026-07-28 transports/streamable-http).

Refs #364"
```

### Task 5: Documents, decision, state

**Files:** `docs/mcp.md`, `docs/mcp.ko.md`, `README.md`, `README.ko.md`, `docs/superpowers/specs/2026-10-07-mistake-patterns-design.md`, `docs/llm-wiki/wiki/decisions/2026-10-07-exam-prep-asks-in-the-open.md`, `docs/llm-wiki/index.md`, `.harness/state/progress.md`

- [ ] **Step 1: `docs/mcp.md`**

1. Add a section after the tools' subsections and before `## Protocol revisions`:

```markdown
## The `exam_prep` prompt

The one place this server asks for interpretation — and it asks in the open. A prompt is text your
client hands its model when you ask for it; the tools still count and name nothing. `exam_prep` asks
your model to find your recurring mistakes in your own repair steps before a coding test:

1. `stats` by part and by level — where passing took the most runs and submits;
2. `repair_steps` — the corrections, grouped into patterns named by what the diffs show, each citing
   the record ids behind it and the number of problems it spans (one occurrence is not a pattern);
3. per pattern, the problems to re-solve and up to three untouched ones in the same part;
4. per pattern, two or three drills aimed at exactly that point;
5. what the records could not support.

It carries its own readings — a run is not an attempt, absent is not zero, run code exists from the
2026-10-07 tracker on, `codeLate`, `incompleteHistory` — because the server instructions have no room
left for them.

In Claude Code it is a slash command, and only you can start it — the model cannot run a prompt on
its own:

    /programmers-tracker:exam_prep                          everything on record
    /programmers-tracker:exam_prep java                     one language
    /programmers-tracker:exam_prep java 2026-09-01          … corrected since a date
    /programmers-tracker:exam_prep mysql 2026-09-01 SELECT  … in one part

The arguments are positional — `language`, `since`, `part` — and Claude Code splits them on spaces
without quoting, so a `since` needs a `language` before it and a `part` needs both. A part name with
a space arrives as its first word ("GROUP" for "GROUP BY"); the prompt tells the model to match it
against the part keys `stats` returns and say which it used. A refusal names the order.
`since` takes the format every tool takes and is refused before anything runs if it does not parse.
Only `repair_steps` takes the scope; `stats` and `list_problems` answer over everything on record.
```

2. In `## What is not built`, change **Genuinely absent** so that only resources are absent: "MCP **resources** (`ps://…`); the server declares the `tools` and `prompts` capabilities." Keep the rest of that paragraph.

- [ ] **Step 2: `README.md`**

The MCP row's state becomes: `**built** — seven read tools and the \`exam_prep\` prompt ([\`mcp.md\`](docs/mcp.md))`.

- [ ] **Step 3: Korean twins**

Mirror both changes in natural Korean in `docs/mcp.ko.md` and `README.ko.md`. Commit the English pages first. Then set each twin's first line to `<!-- translated-from: <source>@<blob sha> -->`, using the blob that `./scripts/guards.sh` compares against (read the script; e.g. `git rev-parse HEAD:docs/mcp.md` after the English commit).

- [ ] **Step 4: Spec status**

In the spec's `Status:` line, change "4.4 not implemented" to "4.4 (#364, live check pending)".

- [ ] **Step 5: The ADR**

Create `docs/llm-wiki/wiki/decisions/2026-10-07-exam-prep-asks-in-the-open.md`. Frontmatter as in `2026-10-07-repair-steps-are-served-not-judged.md`: type decision, project, tags `[mcp, prompt, interpretation-boundary, client-compatibility]`, author, created/updated, and sources (the raw session that exists for these ADRs). Sections in order: Context → Options → Decision (D1–D8, worded as decisions) → Rationale → Accepted costs → Outcome. Link [[decisions/2026-10-07-mistake-patterns-are-diagnosed-not-stored]] and [[decisions/2026-08-12-the-server-counts-and-names-nothing]].

Accepted costs:
- A part given in Claude Code arrives cut at its first space. The model, not the server, reconciles it.
- In Claude Code a `since` needs a `language` before it, and a `part` needs both. The order is the whole interface its menu offers.
- A blank argument counts as not given here, where a tool refuses a blank, because a form-style client sends an empty field as `""`.
- JSON quoting escapes quotes, backslashes and C0 controls, but not Unicode line separators (U+2028/2029, U+0085) or invisible format characters (ZWSP, RLO). A value can therefore still look odd in the text. Only the user types these arguments, and the model cannot run a prompt, so the only one misled is the user's own session.
- No length bound on an argument: the endpoint is loopback-only by default (`TRACKER_BIND_ADDRESS`), behind the token and Origin checks.
- `language` and `part` are unchecked, so a typo answers empty rather than refused; the text tells the model to say so.
- Claude Code reads neither `title` nor the argument descriptions. The argument order is all the help its menu gives.
- The text is English; the client's model chooses the answer's language.

Outcome: live check pending, see Task 6.

Register it in `docs/llm-wiki/index.md` under Decisions, in date order.

- [ ] **Step 6: progress, guards, commit**

Add the progress entry. Run `./scripts/guards.sh` and the three gates. Then check every `[[decisions/…]]` slug the new code links. `guards.sh` deliberately does not, and two KDocs link this ADR ahead of its creation:

```bash
git grep -ohE '\[\[decisions/[^]|]+' -- src/main/kotlin/com/brokenfinger/tracker/adapter/mcp \
  | sort -u | sed 's/\[\[decisions\///' \
  | while read -r slug; do test -f "docs/llm-wiki/wiki/decisions/$slug.md" || echo "dangling: $slug"; done
```

Expected: no output. Commit:

```bash
git commit -m "docs: the exam_prep prompt — how to run it, what it asks, why its arguments are positional

Closes #364"
```

### Task 6 (controller, after merge): live acceptance

1. Rebuild and recreate the container: `docker compose build && docker compose up -d --force-recreate`.
2. Modern `prompts/list` with mirrored headers. Expect `prompts[0].name = exam_prep`, `ttlMs`, `cacheScope: private`, `resultType: complete`.
3. Modern `prompts/get` with `Mcp-Name: exam_prep` and `{language: "mysql"}`. Expect one user message containing `repair_steps(language="mysql")`.
4. Ask the owner to reconnect the MCP server (`/mcp`) or open a new session, then run `/programmers-tracker:exam_prep mysql`. Spec §6: the answer must name patterns that cite record ids.
5. Record the result in the ADR's Outcome and in progress.

## Self-review

- **Spec coverage:** §4.4 steps 1–5 are in Task 2's text. "Prompts capability, one prompt" → Task 4. "The prompt itself must carry these" → Task 2's readings test. §6 tests "prompts/list and prompts/get in both protocol eras" → Task 4. Live acceptance → Task 6.
- **Placeholders:** `#364` is filled when the issue exists. No other placeholders.
- **Type consistency:** these signatures are used the same way in every task:
  - `ExamPrepScope(language, since, part)`, with `paragraph()` and `repairStepsCall()`
  - `ExamPrepPrompt.NAME`, `ExamPrepPrompt.text(scope)`
  - `McpPromptCatalog.NAMES`, `definitions()`, `get(name, arguments)`
  - `McpCall.name()`
  - `aPromptGetParams(name, arguments)`

## Changed in review

Tasks 1–2 passed the spec review byte-identical to this plan. The quality review then changed these. The code blocks above keep the planned text; the code is what review left.

- **Values are quoted as JSON strings**, in the `repair_steps` call and in the scope paragraph (`JsonPrimitive(value).toString()`). "String, Date" and "SUM, MAX, MIN" are real part names; unquoted, a model would read either as two arguments. One ordered `(name, value)` list feeds both renderings.
- **The part warning names what `stats` really returns.** A `groupBy=part` bucket carries the part in `key` and has no `label`. The warning now says to match against the part keys `stats(groupBy=part)` returns, call `repair_steps` with the full name, and say which part was used.
- **An empty answer under a scope is not an absence of mistakes.** `python` (the id is `python3`), or a part typed where `language` goes, narrows `repair_steps` to nothing in silence — the failure D3 refuses for a date. Whenever something narrows, the paragraph tells the model to say so and name the argument that may not match. This makes the Task 5 accepted cost ("the text tells the model to say so") true.
- **Refusals name the order.** A language that reads as a date is checked first. A `since` that does not parse is refused as `Since.FORMAT` plus "the arguments are positional — language, since, part". `/exam_prep mysql SELECT` is the likely slip.
- **Text:**
  - step 1 is spelled as calls (`stats(groupBy=part)`, `stats(groupBy=level)`);
  - the pattern examples gain "a missing table alias" (spec §1, the live records are mostly SQL);
  - `list_problems` is asked with `part=<a part its steps come from>`, since a pattern can span parts;
  - the KDoc says the instructions are "nearly full (#353)" rather than a count that would go stale.
- **Tests pin function, not prose:**
  - the spec-derived asks: group, name by diffs, problems to re-solve, most runs and submits, submit code always kept;
  - each reading's directive;
  - the part instruction;
  - every argument and enum value the text passes, checked to exist in the tool schemas `McpToolCatalog` serves.
- **Task 3 follows:**
  - named arguments into `ExamPrepScope`;
  - "must be text", the tools' wording;
  - a padded `since` reads trimmed;
  - the expected call is quoted.
- **Task 3's quality review:**
  - Unknown argument keys come back JSON-quoted, with the arguments the prompt takes, in order. The unknown-prompt message says "exposes", matching the tools'.
  - The `part` description says what it narrows and why it is last: in last place, the words after the first fall off the end instead of landing in another argument.
  - A table test refuses `5`, `true`, an array and an object under each of the three names.
  - `readScope`, an `Argument` data class, and the 400/200 sentence beside `refused()`.
  - A non-object `arguments` and McpFailure's credential rule move to Task 4 (Step 1b). The characters JSON quoting leaves alone, and the missing length bound, go to Task 5's accepted costs. The KDoc decision links are checked in Task 5 Step 6.
- **Task 4's quality review:**
  - `NAMED` is private.
  - `promptArguments()` is a `when` over absent/null, object, and anything else.
  - McpCall's reader comment and KDoc stopped speaking exam_prep.
  - Both controller opening tests pin the `prompts` capability, and a second end-to-end `prompts/get` carries `language`.
  - The tool path's widening and unquoted keys are #365; the dangling KDoc decision links elsewhere are #366.
- **Task 5's documents follow the code where the plan's draft differed:**
  - `list_problems` takes a part its steps come from;
  - `repair_steps` is called with the full part name;
  - only the three positional-slip refusals name the order.
