package com.brokenfinger.tracker.support.fixtures

import com.brokenfinger.tracker.adapter.store.StateDirectory
import com.brokenfinger.tracker.adapter.store.TrackedState
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

// Object mother for the record repository's state directory (dev rules §6.4, #360). Production
// code has no default for what git tracks — a writer never assumes "nothing" — so a test says it.

/** What git answers in a healthy record repository: nothing tracked under `.ps`, and nothing ever was. */
val NOTHING_TRACKED: TrackedState = object : TrackedState {
    override fun tracksAnything(): Boolean = false

    override fun pathsEverTracked(): Set<String> = emptySet()
}

/** The state directory of [root], with git answering [tracked]. */
fun aStateDirectory(root: Path, tracked: TrackedState = NOTHING_TRACKED): StateDirectory = StateDirectory(root, tracked)

/**
 * Git's answer about `.ps`, changed between calls the way a pull or the owner's `git rm --cached` changes it:
 * what it tracks now, and [history], the paths below `.ps` it has ever tracked. [whileAsked] runs during the
 * question, as another process can act while git is being asked.
 */
class ChangingAnswer(
    var answer: Boolean?,
    var history: Set<String>? = emptySet(),
    private val whileAsked: () -> Unit = {},
) : TrackedState {
    /** How many times git was asked what it has ever tracked. */
    var historyAsked = 0
        private set

    override fun tracksAnything(): Boolean? = answer.also { whileAsked() }

    override fun pathsEverTracked(): Set<String>? = history.also { historyAsked += 1 }
}

/** A root listing whose first read fails, as a passing I/O error does, and then answers from disk. */
fun aListingThatFailsOnce(): (Path) -> Set<String> {
    var failed = false
    return { directory ->
        if (!failed) {
            failed = true
            throw IOException("the listing failed once")
        }
        Files.list(directory).use { entries -> entries.map { it.fileName.toString() }.toList().toSet() }
    }
}
