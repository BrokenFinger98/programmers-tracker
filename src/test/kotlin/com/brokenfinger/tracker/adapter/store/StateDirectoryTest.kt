package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.support.fixtures.A_LONG_S_STATE_DIRECTORY
import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import com.brokenfinger.tracker.support.fixtures.foldsTogether
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Whether what answers to `<records>/.ps` is the tracker's own state directory (#360). Git stores
 * links, and a filesystem may fold names, so a clone or a pull can put something else there — and
 * whatever is written into it is then a path git tracks.
 */
class StateDirectoryTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `an absent state directory is created as a real directory`() {
        val verified = StateDirectory(root).verified()

        verified shouldBe root.resolve(".ps")
        Files.isDirectory(root.resolve(".ps"), LinkOption.NOFOLLOW_LINKS) shouldBe true
    }

    @Test
    fun `a real directory named exactly ps is the state directory`() {
        Files.createDirectory(root.resolve(".ps"))

        StateDirectory(root).verified() shouldBe root.resolve(".ps")
    }

    /** What a pull can deliver: the ignored directory deleted, and a tracked link into the tree in its place. */
    @Test
    fun `a link in its place is not`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val tracked = Files.createDirectories(root.resolve("problems/zz"))
        aLink(root.resolve(".ps"), tracked)

        StateDirectory(root).verified().shouldBeNull()
    }

    @Test
    fun `a file in its place is not`() {
        Files.writeString(root.resolve(".ps"), "not a directory\n")

        StateDirectory(root).verified().shouldBeNull()
    }

    /** A case-insensitive volume answers `.ps` with `.PS`; the name on disk is what decides. */
    @Test
    fun `a directory the filesystem folds to ps is not`() {
        assumeTrue(foldsTogether(root, ".PS", ".ps"), "this filesystem keeps .PS and .ps apart")
        Files.createDirectory(root.resolve(".PS"))

        StateDirectory(root).verified().shouldBeNull()
    }

    /** U+017F folds to `s`, so APFS answers `.ps` with it, and neither the ignore rule nor the pathspec names it. */
    @Test
    fun `a directory with a long s that the filesystem folds to ps is not`() {
        assumeTrue(foldsTogether(root, A_LONG_S_STATE_DIRECTORY, ".ps"), "this filesystem does not fold U+017F")
        Files.createDirectory(root.resolve(A_LONG_S_STATE_DIRECTORY))

        StateDirectory(root).verified().shouldBeNull()
    }

    @Test
    fun `a records directory that is not there is answered, never thrown`() {
        StateDirectory(root.resolve("no/such/records")).verified().shouldBeNull()
    }
}
