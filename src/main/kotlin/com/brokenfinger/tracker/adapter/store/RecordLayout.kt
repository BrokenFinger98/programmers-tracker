package com.brokenfinger.tracker.adapter.store

import java.nio.file.Files
import java.nio.file.Path

/**
 * Where everything lives inside the record repository (design §5.1).
 *
 * Names are built once here so the layout is stated in one place, and so every generated
 * name is checked against the same rule: it must be legal on Windows, because CI runs
 * windows-latest and a record repository is meant to be cloned anywhere.
 */
class RecordLayout(private val root: Path) {
    /**
     * The directory of one problem — `<lessonId>-<slug of title>`.
     *
     * **The lesson id alone is the identity.** If a directory for this id already exists it
     * is returned untouched whatever the title now says, so a problem Programmers renamed
     * keeps one history instead of splitting into two.
     */
    fun problemDirectory(lessonId: Long, title: String?): Path {
        require(lessonId > 0) { "lessonId must be positive: $lessonId" }
        val problems = root.resolve(PROBLEMS)
        return existingDirectoryOf(problems, lessonId) ?: problems.resolve(nameOf(lessonId, title))
    }

    /** The code of one attempt — `attempts/NNN.<ext>`, the extension following its language. */
    fun attemptFile(lessonId: Long, title: String?, attempt: Int, language: String?): Path =
        attemptsOf(lessonId, title, attempt).resolve("${numberOf(attempt)}.${extensionOf(language)}")

    /** The original frames of one attempt, kept beside its code (dev rules §2.4). */
    fun rawAttemptFile(lessonId: Long, title: String?, attempt: Int): Path =
        attemptsOf(lessonId, title, attempt).resolve("${numberOf(attempt)}.raw.jsonl")

    /** The single authority for attempt numbers ([[decisions/2026-08-05-write-serialization.md]]). */
    fun submissionLog(): Path = root.resolve(SUBMISSION_LOG)

    /**
     * One note per catalogued tag — the vault's map of what has and has not been met (#229).
     *
     * The name is slugged by the same rule problem directories use, so a tag the filesystem
     * would not take verbatim still lands in one predictable file. The note's own `tag:` field
     * keeps the original spelling: the path is a path, and only the field may be compared
     * against a record's tags.
     */
    fun tagNote(tag: String): Path = root.resolve(TAGS).resolve("${slugOf(tag)}.md")

    /**
     * What a wikilink to that note must say — the **file name**, not the tag.
     *
     * They differ for 43 of the shipped catalog's 83 tags, because `binary_search` slugs to
     * `binary-search`, and writing the tag instead shipped a map whose links resolved to
     * nothing (#233). Both writers ask here rather than building the string themselves: one
     * place owns the name, for the same reason there is only one sanitiser.
     */
    fun tagNoteLink(tag: String): String = "$TAGS/${slugOf(tag)}"

    private fun attemptsOf(lessonId: Long, title: String?, attempt: Int): Path {
        require(attempt >= 1) { "attempt must be at least 1, a run writes no attempt file: $attempt" }
        return problemDirectory(lessonId, title).resolve(ATTEMPTS)
    }

    private fun existingDirectoryOf(problems: Path, lessonId: Long): Path? {
        if (!Files.isDirectory(problems)) return null
        return Files.list(problems).use { entries ->
            entries.filter { Files.isDirectory(it) && ownedBy(it, lessonId) }.findFirst().orElse(null)
        }
    }

    // Prefix-safe: 1208 must not claim the directory of 120804.
    private fun ownedBy(directory: Path, lessonId: Long): Boolean {
        val name = directory.fileName.toString()
        return name == "$lessonId" || name.startsWith("$lessonId-")
    }

    companion object {
        /** Keeps `<lessonId>-<slug>/attempts/NNN.ext` well inside the Windows path limit. */
        const val MAX_SLUG = 60

        private const val PROBLEMS = "problems"
        private const val TAGS = "tags"
        private const val ATTEMPTS = "attempts"
        private const val SUBMISSION_LOG = "log/submissions.jsonl"
        private const val FALLBACK_EXTENSION = "txt"
        private const val MAX_EXTENSION = 10

        // Everything that is not a letter or a digit collapses to one hyphen. That is stricter
        // than Windows requires, which is the point: it removes the reserved characters, the
        // control characters and the trailing dot or space in a single rule, and it keeps a
        // Korean title readable rather than transliterating it into noise.
        private val UNSAFE = Regex("""[^\p{L}\p{N}]+""")
        private val NOT_EXTENSION = Regex("""[^a-z0-9]""")

        private val EXTENSIONS =
            mapOf(
                "csharp" to "cs",
                "javascript" to "js",
                "kotlin" to "kt",
                "python3" to "py",
                "ruby" to "rb",
                "mysql" to "sql",
                "oracle" to "sql",
            )

        /**
         * The file extension for a language (protocol doc §4 lists 13). A language we have
         * never seen keeps its own name rather than being dropped — a new Programmers language
         * must not cost us the attempt file.
         */
        fun extensionOf(language: String?): String {
            val id = language?.lowercase()?.trim().orEmpty()
            EXTENSIONS[id]?.let { return it }
            val safe = id.replace(NOT_EXTENSION, "").take(MAX_EXTENSION)
            return safe.ifEmpty { FALLBACK_EXTENSION }
        }

        private fun nameOf(lessonId: Long, title: String?): String {
            val slug = slugOf(title)
            return if (slug.isEmpty()) "$lessonId" else "$lessonId-$slug"
        }

        private fun slugOf(title: String?): String =
            title.orEmpty().lowercase().replace(UNSAFE, "-").take(MAX_SLUG).trim('-')

        // Three digits is the shape of the design's `001`; a fourth attempt digit widens the
        // name rather than colliding two attempts onto one file.
        private fun numberOf(attempt: Int): String = attempt.toString().padStart(3, '0')
    }
}
