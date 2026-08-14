package com.brokenfinger.tracker.support.git

import java.nio.file.Files
import java.nio.file.Path

/**
 * A real git repository under a temp directory, with an optional local bare remote.
 *
 * Every git behaviour the wiring depends on — what a partial commit carries, that a push
 * moves the whole branch, that a directory with no `.git` fails — is git's own. A test double
 * would agree with whatever our code believes, which is the wrong oracle; so these tests drive
 * the real binary and never the network (the same posture as `CommandLineGitSyncTest`).
 */
class GitWorkspace(private val base: Path) {
    /** The record repository: an initialised repo with an identity of its own. */
    val root: Path = Files.createDirectories(base.resolve("records"))

    init {
        git("init", "-b", "main")
        git("config", "user.email", "test@example.invalid")
        git("config", "user.name", "Tracker Test")
        // Overrides whatever the developer's global config says, so signing cannot fail a test.
        git("config", "commit.gpgsign", "false")
        // Problem directories carry the Korean title, and git escapes non-ASCII paths in its
        // output by default. That is a property of what we read here, never of what we commit.
        git("config", "core.quotePath", "false")
    }

    /** A bare repository this one already pushed its first commit to. Returns the remote. */
    fun withRemote(): Path {
        val remote = base.resolve("remote.git")
        git("init", "--bare", "-b", "main", remote.toString(), at = base)
        // The same reason as the working repository's copy above, and it was missing here until
        // a test first read *paths* off the remote rather than commit subjects (#316): the bare
        // repo has its own config, so it escaped every Korean problem directory it was asked about.
        git("config", "core.quotePath", "false", at = remote)
        git("remote", "add", "origin", remote.toString())
        write("README.md", "# records")
        git("add", "--all")
        git("commit", "--message", "init")
        git("push", "--set-upstream", "origin", "main")
        return remote
    }

    fun write(relative: String, content: String): Path {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        return Files.writeString(file, content)
    }

    /** Commit subjects, newest first. An unborn branch has no log, which is not a failure. */
    fun subjects(at: Path = root): List<String> {
        val (code, output) = run(listOf("log", "--format=%s"), at)
        if (code != 0) return emptyList()
        return output.trim().lines().filter { it.isNotBlank() }
    }

    fun filesInHead(): List<String> =
        git("show", "--name-only", "--format=").trim().lines().filter { it.isNotBlank() }.sorted()

    /**
     * Every path the tree at HEAD carries — *what this repository holds*, which [filesInHead]
     * (one commit's own diff) does not answer. Defaults to the working repository; pass a bare
     * remote to ask what actually left the machine (#316).
     */
    fun filesAtHead(at: Path = root): List<String> =
        git("ls-tree", "-r", "--name-only", "HEAD", at = at).trim().lines().filter { it.isNotBlank() }.sorted()

    fun statusOf(relative: String): String = git("status", "--porcelain", "--", relative).trim()

    fun git(vararg args: String, at: Path = root): String {
        val (code, output) = run(args.toList(), at)
        check(code == 0) { "git ${args.joinToString(" ")} failed with $code: $output" }
        return output
    }

    private fun run(args: List<String>, at: Path): Pair<Int, String> {
        val process = ProcessBuilder(listOf("git") + args).directory(at.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return process.waitFor() to output
    }
}
