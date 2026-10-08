package com.brokenfinger.tracker.support.fixtures

import com.brokenfinger.tracker.adapter.store.StateDirectory
import com.brokenfinger.tracker.adapter.store.TrackedState
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

// Object mother for the record repository's state directory (dev rules §6.4, #360). Production
// code has no default for what git tracks — a writer never assumes "nothing" — so a test says it.

/** What git answers in a healthy record repository: nothing tracked under `.ps`. */
val NOTHING_TRACKED = TrackedState { false }

/** The state directory of [root], with git answering [tracked]. */
fun aStateDirectory(root: Path, tracked: TrackedState = NOTHING_TRACKED): StateDirectory = StateDirectory(root, tracked)

/**
 * Git's answer about `.ps`, changed between calls the way a pull or the owner's `git rm --cached` changes it.
 * [whileAsked] runs during the question, as another process can act while git is being asked.
 */
class ChangingAnswer(var answer: Boolean?, private val whileAsked: () -> Unit = {}) : TrackedState {
    override fun tracksAnything(): Boolean? = answer.also { whileAsked() }
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
