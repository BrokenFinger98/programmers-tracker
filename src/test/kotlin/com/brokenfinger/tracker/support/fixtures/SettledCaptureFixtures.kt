package com.brokenfinger.tracker.support.fixtures

import com.brokenfinger.tracker.application.CatalogEntry
import com.brokenfinger.tracker.application.GradingSession
import com.brokenfinger.tracker.application.ProblemCatalog
import com.brokenfinger.tracker.application.RawSessionId
import com.brokenfinger.tracker.application.SettledCapture
import com.brokenfinger.tracker.domain.GradingFrameFacts
import com.brokenfinger.tracker.domain.LessonId

// Object mothers (dev rules §6.4). The default capture is the measured passing algorithm
// submit of fixtures/algorithm-pass.jsonl, already settled and ready to be recorded.
// Korean literals are measured values kept verbatim (protocol doc §7).

fun aSettledCapture(
    session: GradingSession = anAssembledSession("algorithm-pass.jsonl"),
    rawSessionId: RawSessionId = aRawSessionId(),
    lessonId: Long = 120804,
    problem: CatalogEntry? = aCatalogEntry(id = lessonId),
    language: String = "java",
    elapsedSec: Long = 847,
    frames: List<String> = acceptedFrames("algorithm-pass.jsonl"),
) = SettledCapture(
    session = session,
    rawSessionId = rawSessionId,
    lessonId = lessonId,
    problem = problem,
    language = language,
    elapsedSec = elapsedSec,
    frames = frames,
)

fun aRawSessionId(name: String = "20260804T142301000Z-120804.jsonl") = RawSessionId(name)

/**
 * The frames of a measured capture that the assembler would accept — the basis the capture
 * key is derived from (#149). Welcome and ping lines are not among them, on the live path or
 * on the replay, so they are not among them here either.
 */
fun acceptedFrames(fixture: String): List<String> = FixtureLoader.broadcastLines(fixture)

/** A measured stream with every terminal frame removed — the shape a timeout leaves (design §4.2). */
fun aTruncatedStream(fixture: String = "algorithm-pass.jsonl"): List<GradingFrameFacts> =
    FixtureLoader.facts(fixture).filter { it.terminalKind == null }

/** What the shipped catalog knows about the default problem (dev rules §6.4). */
fun aCatalogEntry(
    id: Long = 120804,
    title: String = "두 수의 곱 구하기",
    level: Int? = 0,
    partTitle: String? = "코딩테스트 입문",
    acceptanceRate: Int? = 91,
    tags: List<String> = listOf("implementation", "arithmetic"),
) = CatalogEntry(id, title, level, partTitle, acceptanceRate, tags)

/**
 * A catalog holding exactly the entries a test names. Anything else is **absent**, which is
 * the case that matters: the shipped catalog is a snapshot and a problem published after it
 * was built is unknown, so every reader has to survive a miss.
 */
fun aCatalogOf(vararg entries: CatalogEntry): ProblemCatalog = FakeCatalog(entries.associateBy { it.id })

/** A catalog that knows nothing — a fresh install meeting a problem added last week. */
fun anEmptyCatalog(): ProblemCatalog = FakeCatalog(emptyMap())

private class FakeCatalog(private val byId: Map<Long, CatalogEntry>) : ProblemCatalog {
    override fun find(lessonId: LessonId): CatalogEntry? = byId[lessonId.value]

    override fun tagsOf(lessonId: LessonId): List<String> = find(lessonId)?.tags.orEmpty()

    override fun titleOf(lessonId: LessonId): String? = find(lessonId)?.title

    override fun all(): List<CatalogEntry> = byId.values.toList()
}
