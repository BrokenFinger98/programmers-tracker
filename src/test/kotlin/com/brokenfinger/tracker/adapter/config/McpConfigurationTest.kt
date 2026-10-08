package com.brokenfinger.tracker.adapter.config

import com.brokenfinger.tracker.adapter.mcp.McpHeaders
import com.brokenfinger.tracker.adapter.store.FileRawSessionLog
import com.brokenfinger.tracker.adapter.store.RecordLayout
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.aLegacyCall
import com.brokenfinger.tracker.support.fixtures.aRecordRepository
import com.brokenfinger.tracker.support.fixtures.aRepairStepFilter
import com.brokenfinger.tracker.support.fixtures.aRun
import com.brokenfinger.tracker.support.fixtures.aStateDirectory
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import com.brokenfinger.tracker.support.fixtures.aToolCallParams
import com.brokenfinger.tracker.support.fixtures.anEmptyCatalog
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock

/**
 * Wiring, tested without a Spring context ([[decisions/2026-08-04-test-environment]]).
 *
 * What it is really pinning is *which file* the read side opens. Wiring the query to the
 * wrong path would make every tool answer an empty history — a failure that looks exactly
 * like a new user's first day and would therefore never be noticed.
 */
class McpConfigurationTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `points the read side at the submission log of the configured record repository`() {
        aRecordRepository(root).containing(aSubmissionRecord(), aSubmissionRecord())

        McpConfiguration().recordQuery(
            RecordLayout(root),
            anEmptyCatalog(),
            Clock.systemUTC(),
            FileRawSessionLog.under(root, Clock.systemUTC(), aStateDirectory(root)),
        ).history().size shouldBe
            2
    }

    @Test
    fun `assembles a dispatcher that answers a tool call over that repository`() {
        aRecordRepository(root).containing(aSubmissionRecord())
        val configuration = McpConfiguration()
        val query = configuration.recordQuery(
            RecordLayout(root),
            anEmptyCatalog(),
            Clock.systemUTC(),
            FileRawSessionLog.under(root, Clock.systemUTC(), aStateDirectory(root)),
        )

        val dispatcher = configuration.mcpDispatcher(configuration.mcpToolInvoker(query))

        val params = aToolCallParams("stats", buildJsonObject { put("groupBy", "verdict") })
        val response = dispatcher.dispatch(aLegacyCall("tools/call", params), McpHeaders())

        response.status shouldBe 200
        response.body!!["result"]!!.jsonObject["structuredContent"]!!
            .jsonObject["total"]!!.jsonPrimitive.int shouldBe 1
    }

    /**
     * The same failure one level up. Code kept beside the records and wired to nothing reads as
     * "never kept" — every step would say its code is unknown, which is also what the history
     * before 2026-10-07 honestly says, so nobody would notice.
     */
    @Test
    fun `points the read side at the code kept in the configured record repository`() {
        val failed = aRun(at = "2026-10-07T10:00:00+09:00")
        val passed = aRun(at = "2026-10-07T10:00:05+09:00", verdict = Verdict.PASS)
        aRecordRepository(root).containing(failed, passed)
            .withRunCode(failed, "select a").withRunCode(passed, "select b")

        val steps = McpConfiguration().recordQuery(
            RecordLayout(root),
            anEmptyCatalog(),
            Clock.systemUTC(),
            FileRawSessionLog.under(root, Clock.systemUTC(), aStateDirectory(root)),
        ).repairSteps(aRepairStepFilter(), lessonId = null).steps

        steps.single().step.diff.shouldNotBeNull() shouldContain "+select b"
    }

    @Test
    fun `answers an empty repository rather than failing to wire at all`() {
        McpConfiguration().recordQuery(
            RecordLayout(root),
            anEmptyCatalog(),
            Clock.systemUTC(),
            FileRawSessionLog.under(root, Clock.systemUTC(), aStateDirectory(root)),
        ).history().size shouldBe
            0
    }
}
