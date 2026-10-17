package dev.kotrace.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * The release guard: a published version is immutable. The static Maven repo would accept a re-publish with
 * different bytes, and a consumer compiled against the old jar would then fail at runtime. So every remote publish
 * of every project first runs [CheckReleaseVersion], which checks that
 *  - the working tree is clean, since Gradle builds the working tree, not HEAD;
 *  - no `v<version>` tag points at a commit other than HEAD;
 *  - every artifact of this version already in the Maven repo ([REPO_DIR_PROPERTY]) was published from HEAD, as
 *    its POM records ([COMMIT_POM_PROPERTY]); a tag can be moved, the published POM cannot.
 *
 * A `-SNAPSHOT` is mutable by definition, so it keeps only the clean-tree check.
 *
 * Apply it to the root project; it wires every project's publications.
 */
class ReleaseGuardPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        check(project == project.rootProject) { "apply kotrace.release-guard to the root project only" }
        val providers = project.providers
        val headCommit = providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.map { it.trim() }
        val version = project.provider { project.version.toString() }

        val guard = project.tasks.register("checkReleaseVersion", CheckReleaseVersion::class.java) {
            group = "publishing"
            description = "Fails on a dirty working tree, or if a release v<version> is already tagged or published " +
                "from another commit."
            releaseVersion.set(version)
            groupPath.set(project.provider { project.group.toString().replace('.', '/') })
            repoDir.set(providers.gradleProperty(REPO_DIR_PROPERTY))
            this.headCommit.set(headCommit)
            tagCommit.set(
                version.flatMap { v ->
                    providers.exec {
                        commandLine("git", "rev-parse", "-q", "--verify", "refs/tags/v$v^{commit}")
                        isIgnoreExitValue = true
                    }.standardOutput.asText.map { it.trim() }
                },
            )
            workingTreeChanges.set(
                providers.exec { commandLine("git", "status", "--porcelain", "--untracked-files=all") }
                    .standardOutput.asText.map { it.trim() },
            )
        }

        project.allprojects {
            tasks.withType(PublishToMavenRepository::class.java).configureEach { dependsOn(guard) }
            plugins.withId("maven-publish") {
                val publications = extensions.getByType(PublishingExtension::class.java).publications
                    .withType(MavenPublication::class.java)
                // The commit this artifact was built from; the guard reads it back before any re-publish.
                publications.configureEach { pom.properties.put(COMMIT_POM_PROPERTY, headCommit) }
                guard.configure { artifactIds.addAll(project.provider { publications.map { it.artifactId } }) }
            }
        }
    }

    companion object {
        /** The local checkout of the static Maven repo that publishing writes to. */
        const val REPO_DIR_PROPERTY = "kotrace.maven.repo.dir"

        /** The POM property recording the commit an artifact was published from. */
        const val COMMIT_POM_PROPERTY = "kotrace.git.commit"
    }
}

/** See [ReleaseGuardPlugin]. Reads external state (Git, the Maven repo) and produces nothing, so it always runs. */
@DisableCachingByDefault(because = "checks Git and the Maven repo; produces no output")
abstract class CheckReleaseVersion : DefaultTask() {
    @get:Input abstract val releaseVersion: Property<String>
    @get:Input abstract val groupPath: Property<String>
    @get:Input @get:Optional abstract val repoDir: Property<String>
    @get:Input abstract val artifactIds: ListProperty<String>
    @get:Input abstract val headCommit: Property<String>

    /** The commit `v<version>` points at; empty when there is no such tag. */
    @get:Input abstract val tagCommit: Property<String>

    /** `git status --porcelain --untracked-files=all`; empty on a clean tree. */
    @get:Input abstract val workingTreeChanges: Property<String>

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun check() {
        val version = releaseVersion.get()
        val changes = workingTreeChanges.get()
        check(changes.isEmpty()) {
            "kotrace $version: refusing to publish from a dirty working tree — commit or stash first, so " +
                "the artifact is exactly a commit:\n$changes"
        }
        if (version.endsWith("-SNAPSHOT")) return
        val tagged = tagCommit.get()
        val head = headCommit.get()
        check(tagged.isEmpty() || tagged == head) {
            "kotrace $version is already released (tag v$version at $tagged) and HEAD differs. " +
                "A released version is immutable — bump `version` in gradle.properties instead."
        }
        val property = Regex.escape(ReleaseGuardPlugin.COMMIT_POM_PROPERTY)
        val recorded = Regex("<$property>([0-9a-f]+)</$property>")
        val foreign = repoDir.orNull?.let { repo ->
            artifactIds.get().distinct().mapNotNull { artifactId ->
                val dir = File(repo, "${groupPath.get()}/$artifactId/$version")
                if (!dir.exists()) return@mapNotNull null
                val pom = File(dir, "$artifactId-$version.pom")
                val commit = pom.takeIf(File::exists)?.let { recorded.find(it.readText())?.groupValues?.get(1) }
                if (commit == head) null else "$artifactId (published from ${commit ?: "an unknown commit"})"
            }
        }.orEmpty()
        check(foreign.isEmpty()) {
            "kotrace $version is already in the Maven repo from another commit: $foreign. A published version " +
                "is immutable — bump `version` in gradle.properties instead."
        }
    }
}
