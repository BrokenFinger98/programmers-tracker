package com.brokenfinger.tracker.adapter.git

import com.brokenfinger.tracker.support.git.GitWorkspace
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * The token path (#258): `GITHUB_TOKEN` in, a private repository wired as `origin` out.
 *
 * GitHub is faked with the JDK's own HTTP server so every claim here is about **our** requests —
 * what we send, what we do with the answer — and none is about GitHub being up. The one thing a
 * fake cannot prove, that the real API accepts these requests, is the live-verification step of
 * the PR.
 */
class GithubRemoteTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var server: HttpServer
    private lateinit var repo: GitWorkspace
    private val requests = mutableListOf<Pair<String, String>>()
    private var createStatus = 201
    private var repoPrivate = true

    @BeforeEach
    fun fakeGithub() {
        repo = GitWorkspace(dir)
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            requests += "${exchange.requestMethod} ${exchange.requestURI.path}" to body
            val answer = answerFor(exchange.requestMethod, exchange.requestURI.path)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(answer.first, 0)
            exchange.responseBody.use { it.write(answer.second.encodeToByteArray()) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun answerFor(method: String, path: String): Pair<Int, String> = when {
        path == "/user" -> 200 to """{"login":"tester"}"""
        method == "POST" && path == "/user/repos" ->
            createStatus to
                """{"private":$repoPrivate,"clone_url":"https://github.com/tester/${repo.root.fileName}.git"}"""
        path.startsWith("/repos/tester/") ->
            200 to """{"private":$repoPrivate,"clone_url":"https://github.com/tester/${repo.root.fileName}.git",
                       "permissions":{"push":true}}"""
        else -> 404 to "{}"
    }

    private fun remote(token: String? = "ghp_test_token") =
        GithubRemote(repo.root, token?.let(::GithubToken), apiBase = "http://127.0.0.1:${server.address.port}")

    @Test
    fun `creates a private repository and wires it as origin`() {
        remote().ensure()

        val creation = requests.single { it.first == "POST /user/repos" }
        creation.second shouldContain "\"private\":true"
        git("remote", "get-url", "origin").trim() shouldBe "https://github.com/tester/${repo.root.fileName}.git"
    }

    /** The name GitHub gets is the directory the records actually live in. */
    @Test
    fun `names the repository after the record directory`() {
        remote().ensure()

        requests.single { it.first == "POST /user/repos" }.second shouldContain "\"name\":\"${repo.root.fileName}\""
    }

    /**
     * **The one assertion that guards the nightmare.** A repository that comes back public —
     * an API change, a mis-set org default — must not be wired: pushing solving history to a
     * public repository is the failure the whole design exists to avoid.
     */
    @Test
    fun `refuses to wire a repository the API says is public`() {
        repoPrivate = false

        remote().ensure()

        git("remote").trim() shouldBe ""
    }

    /**
     * 422 means the name already exists — the second boot after a first boot that wired but
     * crashed before recording it, or a repository the user made themselves. The existing one
     * is looked up and used **only after the same private check**.
     */
    @Test
    fun `an already existing repository is looked up and wired, not recreated`() {
        createStatus = 422

        remote().ensure()

        requests.map { it.first }.contains("GET /repos/tester/${repo.root.fileName}").shouldBeTrue()
        git("remote", "get-url", "origin").trim() shouldBe "https://github.com/tester/${repo.root.fileName}.git"
    }

    /**
     * A GitHub SSH origin is repointed at HTTPS. "Never touch an existing origin" protected
     * someone who chose SSH deliberately — and SSH is retired with `openssh-client` gone from
     * the image, so leaving it would guarantee a push that cannot succeed rather than respect
     * a choice. Same repository, and only when a token exists to authenticate it (#258).
     */
    @Test
    fun `a github ssh origin is repointed at https`() {
        git("remote", "add", "origin", "git@github.com:tester/elsewhere.git")

        remote().ensure()

        git("remote", "get-url", "origin").trim() shouldBe "https://github.com/tester/elsewhere.git"
        requests.map { it.first } shouldBe emptyList()
        Files.readString(repo.root.resolve(".ps/git-credentials")) shouldContain "x-access-token:"
    }

    /** The other SSH spelling GitHub hands out. */
    @Test
    fun `the ssh protocol spelling is converted too`() {
        git("remote", "add", "origin", "ssh://git@github.com/tester/elsewhere.git")

        remote().ensure()

        git("remote", "get-url", "origin").trim() shouldBe "https://github.com/tester/elsewhere.git"
    }

    /**
     * Another host is untouched: this token cannot authenticate GitLab, so rewiring would trade
     * a working setup for a broken one.
     */
    @Test
    fun `an ssh remote on another host is left alone`() {
        git("remote", "add", "origin", "git@gitlab.com:tester/elsewhere.git")

        remote().ensure()

        git("remote", "get-url", "origin").trim() shouldBe "git@gitlab.com:tester/elsewhere.git"
    }

    /** An HTTPS origin is already right, whoever wired it. */
    @Test
    fun `an existing https origin is left exactly as it is`() {
        git("remote", "add", "origin", "https://github.com/tester/somewhere-else.git")

        remote().ensure()

        git("remote", "get-url", "origin").trim() shouldBe "https://github.com/tester/somewhere-else.git"
    }

    /** No token, no authority to change anything — including an SSH URL we cannot replace. */
    @Test
    fun `without a token even a github ssh origin is untouched`() {
        git("remote", "add", "origin", "git@github.com:tester/elsewhere.git")

        remote(token = null).ensure()

        git("remote", "get-url", "origin").trim() shouldBe "git@github.com:tester/elsewhere.git"
    }

    @Test
    fun `no token means no requests and no remote`() {
        remote(token = null).ensure()

        requests.map { it.first } shouldBe emptyList()
        git("remote").trim() shouldBe ""
    }

    /**
     * Pushes authenticate through a credential store the server writes — owner-only, inside the
     * gitignored `.ps/`, exactly like the watch token. The remote URL itself stays clean, so a
     * failed push logged with git's own words can never contain the token.
     */
    @Test
    fun `stores the credential owner-only and keeps the token out of the remote url`() {
        remote().ensure()

        val credentials = repo.root.resolve(".ps/git-credentials")
        Files.readString(credentials) shouldContain "x-access-token:ghp_test_token@github.com"
        // Windows has no POSIX permissions and the production code degrades rather than failing
        // there, so the assertion is skipped rather than the platform being excluded.
        assumeTrue(credentials.fileSystem.supportedFileAttributeViews().contains("posix"))
        Files.getPosixFilePermissions(credentials).let { perms ->
            perms.contains(PosixFilePermission.OWNER_READ).shouldBeTrue()
            perms.none { it.name.startsWith("GROUP") || it.name.startsWith("OTHERS") }.shouldBeTrue()
        }
        git("remote", "get-url", "origin") shouldNotContain "ghp_test_token"
    }

    /**
     * The repository's config is the **user's**, and the path we would write into it is ours:
     * inside a container it is `/records/…`, on their host it does not exist. Writing it there
     * made every host-side push print `fatal: unable to get credential storage lock` over a push
     * that had already succeeded (#267). The pointer is passed per command now — see
     * [PushCredential] — and this asserts the config we leave behind is empty.
     */
    @Test
    fun `writes no credential pointer into the repository's own config`() {
        remote().ensure()

        localHelpers().shouldBeEmpty()
    }

    /** An install from before #267 carries the entry, and nothing else will ever remove it. */
    @Test
    fun `removes the pointer an earlier version wrote`() {
        git("config", "--local", "credential.helper", "store --file=/records/.ps/git-credentials")

        remote().ensure()

        localHelpers().shouldBeEmpty()
    }

    /** Including when there is no token at all: the fatal does not wait for one. */
    @Test
    fun `removes it even with no token, because the message it causes does not need one`() {
        git("config", "--local", "credential.helper", "store --file=/records/.ps/git-credentials")

        remote(token = null).ensure()

        localHelpers().shouldBeEmpty()
    }

    /**
     * `store --file=~/.git-credentials` is git's own documented default, so a filter looser than
     * our exact file name would delete a setting that was never ours.
     */
    @Test
    fun `leaves a credential helper the user set themselves`() {
        git("config", "--local", "--add", "credential.helper", "store --file=/home/someone/.git-credentials")
        git("config", "--local", "--add", "credential.helper", "store --file=/records/.ps/git-credentials")

        remote().ensure()

        localHelpers() shouldBe listOf("store --file=/home/someone/.git-credentials")
    }

    private fun localHelpers(): List<String> =
        git("config", "--local", "--get-all", "credential.helper").lines().filter { it.isNotBlank() }

    /** Boot twice, converge once. */
    @Test
    fun `is idempotent across boots`() {
        remote().ensure()
        val urls = git("remote", "get-url", "origin")

        remote().ensure()

        git("remote", "get-url", "origin") shouldBe urls
    }

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(repo.root.toFile())
            .redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return out
    }
}
