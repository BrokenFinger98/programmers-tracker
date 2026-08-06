package com.brokenfinger.tracker.adapter.catalog

import com.brokenfinger.tracker.application.CatalogEntry
import com.brokenfinger.tracker.application.ProblemCatalog
import com.brokenfinger.tracker.domain.LessonId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * The problem catalog, read once from the jar and held in memory.
 *
 * **Why it ships rather than being fetched.** The labels were produced by reading 536 problem
 * statements — the one part of this project that makes a request per problem rather than per
 * thing the user did. Shipping the result means those requests happen once, ever, instead of
 * once per user ([[decisions/2026-08-06-shipped-problem-catalog]]). Nothing here fetches
 * anything; this class only reads a file that is already inside the jar.
 *
 * **It goes stale by construction.** It is a snapshot of somebody else's catalog, so a problem
 * added afterwards is simply missing. Every lookup therefore answers with absence rather than
 * an error, and callers treat a miss as ordinary — a record for an unknown lesson is still a
 * valid record, just one without a title from here.
 */
class ClasspathProblemCatalog private constructor(
    private val byId: Map<Long, CatalogEntry>,
    private val provenance: Map<String, String>,
) : ProblemCatalog {
    override fun find(lessonId: LessonId): CatalogEntry? = byId[lessonId.value]

    override fun tagsOf(lessonId: LessonId): List<String> = find(lessonId)?.tags.orEmpty()

    override fun titleOf(lessonId: LessonId): String? = find(lessonId)?.title

    fun all(): Collection<CatalogEntry> = byId.values

    fun size(): Int = byId.size

    /** How the file was produced, carried with it so the collection cost stays answerable. */
    fun provenance(): Map<String, String> = provenance

    companion object {
        private const val CATALOG = "/catalog.json"
        private const val VOCABULARY = "/tag-vocab.json"

        // The catalog is written by a tool, not by hand, and gains fields before the reader
        // learns about them — the same posture the protocol parsers take (dev rules §2.2).
        private val json = Json { ignoreUnknownKeys = true }

        private val logger = LoggerFactory.getLogger(ClasspathProblemCatalog::class.java)

        fun load(): ClasspathProblemCatalog {
            val document = json.decodeFromString<CatalogDocument>(resource(CATALOG))
            val byId = document.problems.associate { it.id to it.toEntry() }
            logger.info("Loaded {} catalogued problems, built {}", byId.size, document.generatedAt)
            return ClasspathProblemCatalog(byId, document.provenance)
        }

        /** The adopted tag vocabulary, shipped for the reason development-rules §8 gives. */
        fun vocabulary(): Set<String> =
            json.decodeFromString<VocabularyDocument>(resource(VOCABULARY)).tags.map { it.key }.toSet()

        private fun resource(name: String): String = ClasspathProblemCatalog::class.java.getResourceAsStream(name)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("$name is missing from the jar — the build did not include it")
    }
}

@Serializable
private data class CatalogDocument(
    val schema: Int,
    @SerialName("generatedAt") val generatedAt: String,
    val provenance: Map<String, String> = emptyMap(),
    val problems: List<CatalogProblem> = emptyList(),
)

@Serializable
private data class CatalogProblem(
    val id: Long,
    val title: String,
    val level: Int? = null,
    val partTitle: String? = null,
    val acceptanceRate: Int? = null,
    val tags: List<String> = emptyList(),
) {
    fun toEntry() = CatalogEntry(id, title, level, partTitle, acceptanceRate, tags)
}

@Serializable
private data class VocabularyDocument(val count: Int, val tags: List<VocabularyTag> = emptyList())

@Serializable
private data class VocabularyTag(val key: String)
