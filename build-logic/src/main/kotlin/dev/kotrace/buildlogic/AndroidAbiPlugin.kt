package dev.kotrace.buildlogic

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import org.jetbrains.kotlin.abi.tools.AbiFilters
import org.jetbrains.kotlin.abi.tools.AbiTools
import javax.inject.Inject

/**
 * The binary-compatibility gate for an Android library, the one the Kotlin Gradle plugin's `abiValidation()` gives
 * the JVM modules. Under AGP 9's built-in Kotlin, `abiValidation()` registers its tasks but never collects the
 * compiled classes (KGP 2.4.10 and 2.4.20): its dump comes out empty, so a gate on it would pass any change.
 *
 * This runs the same dumper (`org.jetbrains.kotlin:abi-tools` at the compiler's version, so the format matches the
 * JVM modules' dumps) over the release variant's classes, under the same task names and dump location:
 * `checkKotlinAbi` (run by `check`) compares against `api/<project>.api`, and `updateKotlinAbi` rewrites it.
 *
 * Apply it next to `com.android.library`.
 */
class AndroidAbiPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("com.android.library") { configure(project) }
    }

    private fun configure(project: Project) {
        val libs = project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        val abiTools = project.configurations.dependencyScope("kotlinAbiTools")
        project.dependencies.addProvider(abiTools.name, libs.findLibrary("kotlin-abi-tools").get())
        val abiToolsClasspath = project.configurations.resolvable("kotlinAbiToolsClasspath") {
            extendsFrom(abiTools.get())
        }

        val dump = project.tasks.register("dumpKotlinAbi", DumpKotlinAbi::class.java) {
            description = "Dumps the public ABI of the release variant's classes into the build directory."
            toolsClasspath.from(abiToolsClasspath)
            dumpFile.set(project.layout.buildDirectory.file("kotlin/abi/${project.name}.api"))
        }
        val android = project.extensions.getByType(LibraryAndroidComponentsExtension::class.java)
        android.onVariants(android.selector().withBuildType("release")) { variant ->
            variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT).use(dump)
                .toGet(ScopedArtifact.CLASSES, DumpKotlinAbi::jars, DumpKotlinAbi::directories)
        }

        val reference = project.layout.projectDirectory.file("api/${project.name}.api")
        val check = project.tasks.register("checkKotlinAbi", CheckKotlinAbi::class.java) {
            group = "verification"
            description = "Checks the release variant's public ABI against the committed reference dump."
            actualDump.set(dump.flatMap { it.dumpFile })
            referenceDump.set(project.provider { reference.takeIf { it.asFile.exists() } })
            referencePath.set(project.rootProject.relativePath(reference.asFile))
        }
        project.tasks.register("updateKotlinAbi", Copy::class.java) {
            group = "other"
            description = "Writes the release variant's public ABI to the reference dump file."
            from(dump.flatMap { it.dumpFile })
            into(reference.asFile.parentFile)
        }
        project.tasks.named("check") { dependsOn(check) }
    }
}

/** See [AndroidAbiPlugin]. Runs the dumper in an isolated worker, so its Kotlin never meets Gradle's. */
@CacheableTask
abstract class DumpKotlinAbi : DefaultTask() {
    @get:Classpath abstract val toolsClasspath: ConfigurableFileCollection

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val jars: ListProperty<RegularFile>

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val directories: ListProperty<Directory>

    @get:OutputFile abstract val dumpFile: RegularFileProperty

    @get:Inject abstract val workers: WorkerExecutor

    @TaskAction
    fun dump() {
        val classes = directories.get().flatMap { it.asFileTree.matching { include("**/*.class") }.files } +
            jars.get().map { it.asFile }
        workers.classLoaderIsolation { classpath.from(toolsClasspath) }.submit(DumpJvmAbi::class.java) {
            classfiles.from(classes)
            output.set(dumpFile)
        }
    }
}

/** The worker half of [DumpKotlinAbi]; loaded with abi-tools. */
abstract class DumpJvmAbi : WorkAction<DumpJvmAbi.Parameters> {
    interface Parameters : WorkParameters {
        val classfiles: ConfigurableFileCollection
        val output: RegularFileProperty
    }

    override fun execute() {
        val tools = AbiTools.getInstance(DumpJvmAbi::class.java.classLoader)
        parameters.output.get().asFile.bufferedWriter().use { writer ->
            tools.printJvmDump(writer, parameters.classfiles.files, AbiFilters.EMPTY)
        }
    }
}

/** See [AndroidAbiPlugin]. Fails with the changed lines, or when no reference dump is committed yet. */
@DisableCachingByDefault(because = "compares two small files")
abstract class CheckKotlinAbi : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val actualDump: RegularFileProperty

    // Optional and absent when the file is missing, so the check reports it instead of Gradle failing validation.
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE)
    abstract val referenceDump: RegularFileProperty

    @get:Input abstract val referencePath: Property<String>

    @TaskAction
    fun check() {
        val path = referencePath.get()
        val reference = referenceDump.orNull?.asFile
            ?: error("No reference ABI dump at $path. Run `./gradlew updateKotlinAbi` and commit it.")
        val expected = reference.readLines()
        val actual = actualDump.get().asFile.readLines()
        if (expected == actual) return
        val removed = expected.filter { it !in actual }.map { "- $it" }
        val added = actual.filter { it !in expected }.map { "+ $it" }
        error(
            "The public ABI differs from $path:\n${(removed + added).joinToString("\n")}\n" +
                "Review the change. If it is intended, run `./gradlew updateKotlinAbi` and commit the dump; a " +
                "removed or changed line is a binary break and needs a version bump.",
        )
    }
}
