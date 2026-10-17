package dev.kotrace.buildlogic

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The release guard end to end: a throwaway Git repository holding a published module and a BOM, publishing into a
 * throwaway Maven directory the way the real build publishes into its static repository checkout.
 */
class ReleaseGuardPluginTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var project: File
    private lateinit var repo: File

    @Before fun fixture() {
        project = tmp.newFolder("project")
        repo = tmp.newFolder("maven-repo")
        project.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "lib"
            include(":bom")
            """.trimIndent(),
        )
        project.resolve("build.gradle.kts").writeText(
            """
            plugins {
                `java-library`
                `maven-publish`
                id("kotrace.release-guard")
            }
            allprojects {
                group = "dev.example"
                version = providers.gradleProperty("release").get()
            }
            publishing {
                publications { create<MavenPublication>("maven") { from(components["java"]) } }
                repositories { maven { name = "Repo"; url = uri(providers.gradleProperty("kotrace.maven.repo.dir").get()) } }
            }
            """.trimIndent(),
        )
        project.resolve("bom").mkdirs()
        project.resolve("bom/build.gradle.kts").writeText(
            """
            plugins {
                `java-platform`
                `maven-publish`
            }
            publishing {
                publications { create<MavenPublication>("bom") { from(components["javaPlatform"]) } }
                repositories { maven { name = "Repo"; url = uri(providers.gradleProperty("kotrace.maven.repo.dir").get()) } }
            }
            """.trimIndent(),
        )
        project.resolve(".gitignore").writeText(".gradle/\nbuild/\n.kotlin/\n")
        setVersion("1.0.0")
        git("init", "-q")
        commit("initial")
    }

    @Test
    fun `a first publish records HEAD in each POM, and HEAD may publish that version again`() {
        publish()
        val head = git("rev-parse", "HEAD")
        assertEquals(head, recordedCommit("lib", "1.0.0"))
        assertEquals(head, recordedCommit("bom", "1.0.0"))

        publish()
    }

    @Test
    fun `another commit cannot overwrite a version already in the repository`() {
        publish()
        val published = git("rev-parse", "HEAD")
        commit("change")

        val failure = publishAndFail()

        assertTrue(failure.output, failure.output.contains("already in the Maven repository from another commit"))
        assertTrue(failure.output, failure.output.contains("lib (published from $published)"))
    }

    @Test
    fun `a version in the repository with no recorded commit blocks the publish`() {
        val dir = repo.resolve("dev/example/lib/1.0.0").apply { mkdirs() }
        dir.resolve("lib-1.0.0.pom").writeText("<project></project>")

        val failure = publishAndFail()

        assertTrue(failure.output, failure.output.contains("lib (published from an unknown commit)"))
    }

    @Test
    fun `a dirty working tree cannot publish`() {
        project.resolve("untracked.txt").writeText("not committed")

        val failure = publishAndFail()

        assertTrue(failure.output, failure.output.contains("refusing to publish from a dirty working tree"))
        assertTrue("nothing reached the repository", repo.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a release tag on another commit blocks the publish`() {
        git("tag", "v1.0.0")
        commit("after the tag")

        val failure = publishAndFail()

        assertTrue(failure.output, failure.output.contains("is already released (tag v1.0.0"))
    }

    @Test
    fun `the BOM's publish is guarded too`() {
        publish()
        commit("change")

        val failure = publishAndFail(":bom:publish")

        assertTrue(failure.output, failure.output.contains("already in the Maven repository from another commit"))
    }

    @Test
    fun `a snapshot re-publishes from any commit, but never from a dirty tree`() {
        setVersion("1.1.0-SNAPSHOT")
        commit("snapshot")
        publish()
        commit("change")
        git("tag", "v1.1.0-SNAPSHOT", "HEAD~1")

        publish() // Maven names each snapshot publish by timestamp; there is no fixed POM to compare against

        project.resolve("untracked.txt").writeText("not committed")
        val failure = publishAndFail()
        assertTrue(failure.output, failure.output.contains("refusing to publish from a dirty working tree"))
    }

    private fun setVersion(version: String) {
        project.resolve("gradle.properties").writeText("release=$version\nkotrace.maven.repo.dir=${repo.absolutePath}\n")
    }

    private fun runner(task: String) = GradleRunner.create()
        .withProjectDir(project)
        .withPluginClasspath()
        .withArguments(task, "--stacktrace")

    private fun publish(task: String = "publish"): BuildResult = runner(task).build()

    private fun publishAndFail(task: String = "publish"): BuildResult = runner(task).buildAndFail()

    private fun recordedCommit(artifactId: String, version: String): String? {
        val pom = repo.resolve("dev/example/$artifactId/$version/$artifactId-$version.pom").readText()
        return Regex("<kotrace.git.commit>([0-9a-f]+)</kotrace.git.commit>").find(pom)?.groupValues?.get(1)
    }

    private fun commit(message: String) {
        project.resolve("change.txt").appendText("$message\n")
        git("add", "-A")
        git("commit", "-q", "-m", message)
    }

    /** Git with the user's global configuration neutralised: no signing, no hooks, a fixed identity. */
    private fun git(vararg args: String): String {
        val command = listOf(
            "git", "-c", "user.name=test", "-c", "user.email=test@example.com", "-c", "commit.gpgsign=false",
            "-c", "tag.gpgsign=false", "-c", "core.hooksPath=/dev/null",
        ) + args
        val process = ProcessBuilder(command).directory(project).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "${args.joinToString(" ")} failed: $output" }
        return output
    }
}
