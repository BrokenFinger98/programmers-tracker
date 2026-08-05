package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.adapter.git.CommandLineGitSync
import com.brokenfinger.tracker.support.git.GitWorkspace
import com.brokenfinger.tracker.support.lock.LockHolder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The exclusive record-repository lock ([[decisions/2026-08-05-write-serialization]]
 * decision 5).
 *
 * Two of these tests start a **real second JVM**. That is deliberate and it is the point of
 * the class: the whole hazard is a container and a native run bound to the same records
 * directory, and a same-JVM test cannot distinguish "the kernel refused the second process"
 * from "the JVM refused itself". Both refusals matter, so both are tested — separately.
 */
class RecordRepositoryLockTest {
    @Test
    @Timeout(TIMEOUT)
    fun `a lock another process holds refuses this one`(@TempDir base: Path) {
        val records = recordsIn(base)

        LockHolder.start(records).use { holder ->
            holder.outcome() shouldBe LockHolder.ACQUIRED

            val refused = shouldThrow<RecordRepositoryLockedException> { RecordRepositoryLock(records) }
            refused.message.orEmpty() shouldContain records.toString()
        }
    }

    @Test
    @Timeout(TIMEOUT)
    fun `a holder killed without warning leaves no lock behind`(@TempDir base: Path) {
        val records = recordsIn(base)
        val holder = LockHolder.start(records)
        holder.outcome() shouldBe LockHolder.ACQUIRED

        // SIGKILL: the process gets no chance to release anything. The operating system does
        // it instead, which is the whole reason this is a file lock and not a pid file.
        holder.killHard()

        RecordRepositoryLock(records).use { it.file.parent shouldBe records }
    }

    @Test
    @Timeout(TIMEOUT)
    fun `the same JVM taking the lock twice is refused as well`(@TempDir base: Path) {
        val records = recordsIn(base)

        RecordRepositoryLock(records).use {
            // A different answer from the same question: java.nio throws
            // OverlappingFileLockException here rather than returning null, and a refusal
            // that only handled the null would let a second context boot inside one JVM.
            shouldThrow<RecordRepositoryLockedException> { RecordRepositoryLock(records) }
        }
    }

    @Test
    @Timeout(TIMEOUT)
    fun `a released lock can be taken again`(@TempDir base: Path) {
        val records = recordsIn(base)

        RecordRepositoryLock(records).close()

        RecordRepositoryLock(records).use { Files.exists(it.file).shouldBeTrue() }
    }

    @Test
    @Timeout(TIMEOUT)
    fun `a second process may take the lock once this one released it`(@TempDir base: Path) {
        val records = recordsIn(base)
        RecordRepositoryLock(records).close()

        LockHolder.start(records).use { it.outcome() shouldBe LockHolder.ACQUIRED }
    }

    @Test
    @Timeout(TIMEOUT)
    fun `a record repository that does not exist yet is created and locked`(@TempDir base: Path) {
        val records = base.resolve("not-created-yet")

        RecordRepositoryLock(records).use {
            Files.isDirectory(records).shouldBeTrue()
            Files.exists(it.file).shouldBeTrue()
        }
    }

    @Test
    @Timeout(TIMEOUT)
    fun `the lock lives inside git so that nothing can stage it`(@TempDir base: Path) {
        val workspace = GitWorkspace(base)

        RecordRepositoryLock(workspace.root).use {
            it.file shouldBe workspace.root.resolve(".git").resolve(RecordRepositoryLock.LOCK_FILE)
        }
    }

    /**
     * The trap this placement exists for. `reconcile` runs `git add --all` over the whole
     * record repository, so a lock file anywhere git can see would be committed — and then
     * pushed to a public records repository on the next pass.
     *
     * Asserted against what git actually committed rather than against `.gitignore`: an
     * ignore rule is a claim about a file, and the commit is the fact.
     */
    @Test
    @Timeout(TIMEOUT)
    fun `the lock file never reaches a commit`(@TempDir base: Path) {
        val workspace = GitWorkspace(base)
        workspace.write("log/submissions.jsonl", """{"lessonId":120804}""")

        RecordRepositoryLock(workspace.root).use {
            CommandLineGitSync(workspace.root).reconcile().shouldBeTrue()
        }

        workspace.filesInHead() shouldContainExactly listOf("log/submissions.jsonl")
        workspace.statusOf(".") shouldBe ""
    }

    /**
     * A linked worktree keeps a `.git` **file** rather than a directory, and its working tree
     * is staged by `git add --all` like any other. Following the pointer keeps the lock out
     * of the commit in that layout too.
     */
    @Test
    @Timeout(TIMEOUT)
    fun `a repository whose git is a file locks inside the real git directory`(@TempDir base: Path) {
        val gitDir = Files.createDirectories(base.resolve("elsewhere/worktrees/records"))
        val records = Files.createDirectories(base.resolve("linked"))
        Files.writeString(records.resolve(".git"), "gitdir: $gitDir\n")

        RecordRepositoryLock(records).use { it.file shouldBe gitDir.resolve(RecordRepositoryLock.LOCK_FILE) }
    }

    /**
     * A records directory nobody ran `git init` on has no `.git`, so the lock falls back to
     * the repository root. That is still cross-process — the mount is what is shared, not
     * the repository — and a directory with no repository has nothing that could commit it.
     */
    @Test
    @Timeout(TIMEOUT)
    fun `a directory that is not a repository locks at its root`(@TempDir base: Path) {
        val records = recordsIn(base)

        RecordRepositoryLock(records).use { it.file shouldBe records.resolve(RecordRepositoryLock.LOCK_FILE) }
    }

    /**
     * The one window the root fallback opens: a run before `git init`, then `git init`, then
     * a run after it. The second run locks inside `.git` and the first run's file would sit
     * in the working tree waiting for `git add --all`. It is removed instead.
     */
    @Test
    @Timeout(TIMEOUT)
    fun `a root lock left by a pre-init run is removed once git exists`(@TempDir base: Path) {
        val workspace = GitWorkspace(base)
        val stale = Files.createFile(workspace.root.resolve(RecordRepositoryLock.LOCK_FILE))

        RecordRepositoryLock(workspace.root).use {
            Files.exists(stale).shouldBeFalse()
            workspace.statusOf(".") shouldBe ""
        }
    }

    /**
     * Belt for the same window's braces: a template-derived repository ignores the name even
     * if the removal above never runs. Asserted here so the two can never drift apart.
     */
    @Test
    fun `the template record repository ignores the root lock name`() {
        Files.readString(Path.of("template/ps-records/.gitignore")) shouldContain RecordRepositoryLock.LOCK_FILE
    }

    @Test
    @Timeout(TIMEOUT)
    fun `the refusal names the repository and what to do, and carries no credential`(@TempDir base: Path) {
        val records = recordsIn(base)

        RecordRepositoryLock(records).use {
            val refused = shouldThrow<RecordRepositoryLockedException> { RecordRepositoryLock(records) }

            refused.message.orEmpty() shouldContain records.toString()
            refused.recordRoot shouldBe records
            refused.message.orEmpty() shouldStartWith "Another programmers-tracker"
        }
    }

    private fun recordsIn(base: Path): Path = Files.createDirectories(base.resolve("records"))

    private companion object {
        /** Two child JVMs at worst; a runner that needs longer than this has hung. */
        const val TIMEOUT = 120L
    }
}
