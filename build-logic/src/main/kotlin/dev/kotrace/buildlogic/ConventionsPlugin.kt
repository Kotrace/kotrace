package dev.kotrace.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.provider.Provider
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.kotlin.gradle.dsl.HasConfigurableKotlinCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import java.io.File
import java.util.Properties

/**
 * The conventions every published kotrace module shares, in one place so a new module (Ktor, a KMP target) cannot
 * drift from them. Each part waits for the plugin it configures, so a module applies this next to its own plugins
 * and keeps only what is particular to it:
 * - **Kotlin, on the JVM or on Android:** the consumer baseline (ADR-027) and the `KotraceIntegrationApi` opt-in
 *   (ADR-035), which binds the module that declares it too.
 * - **Kotlin on the JVM:** Java 11 bytecode compiled against the Java 11 API, the ABI gate, a sources jar, the
 *   `KotraceTestApi` opt-in for tests, and [CheckAndroidApi] on the jar, since these modules run on Android too.
 * - **Dokka:** warnings fail the documentation build.
 * - **`maven-publish`:** the POM identity, whose description is the project's `description`, and the static Maven
 *   repository target set with `kotrace.maven.repo.dir`.
 */
class ConventionsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            configureKotlin(project)
            configureJvm(project)
        }
        project.pluginManager.withPlugin("com.android.library") { configureKotlin(project) }
        project.pluginManager.withPlugin("org.jetbrains.dokka") {
            project.extensions.getByType(DokkaExtension::class.java).dokkaPublications.configureEach {
                failOnWarning.set(true)
            }
        }
        project.pluginManager.withPlugin("maven-publish") { configurePublishing(project) }
    }

    private fun configureKotlin(project: Project) {
        val libs = project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        val kotlin = project.kotlin
        kotlin.coreLibrariesVersion = libs.findVersion("kotlinStdlibBaseline").get().requiredVersion
        kotlin.jvmCompilerOptions.apply {
            val baseline = KotlinVersion.fromVersion(libs.findVersion("kotlinBaseline").get().requiredVersion)
            languageVersion.set(baseline)
            apiVersion.set(baseline)
            // Pinned, although language 2.2 defaults to it, so the ABI never follows the baseline: below 2.2 the
            // default compiles interface default methods as abstract plus DefaultImpls, which makes a Java
            // implementor override every method.
            jvmDefault.set(JvmDefaultMode.ENABLE)
            optIn.add("dev.kotrace.bridge.KotraceIntegrationApi")
        }
    }

    @OptIn(ExperimentalAbiValidation::class)
    private fun configureJvm(project: Project) {
        project.extensions.configure(JavaPluginExtension::class.java) {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
            withSourcesJar()
        }
        val kotlin = project.kotlin
        // `check` compares against the committed `api/` dump; `updateKotlinAbi` writes it.
        kotlin.abiValidation()
        kotlin.jvmCompilerOptions.apply {
            jvmTarget.set(JvmTarget.JVM_11)
            // The target alone sets the bytecode version: without this, the compiler accepts any API of the JDK it
            // runs on, and a call added in Java 12 or later fails only at run time on a consumer's Java 11.
            freeCompilerArgs.add("-Xjdk-release=11")
        }
        // Tests may reset the install-once state; production code cannot (`KotraceTestApi` is ERROR level).
        kotlin.sourceSets.named("test") { languageSettings.optIn("dev.kotrace.KotraceTestApi") }

        val libs = project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        val compileSdk = libs.findVersion("androidCompileSdk").get().requiredVersion
        val database = androidSdk(project).map { File(it, "platforms/android-$compileSdk/data/api-versions.xml") }
        val checkAndroidApi = project.tasks.register("checkAndroidApi", CheckAndroidApi::class.java) {
            group = "verification"
            description = "Checks that the jar calls no Java API Android lacks at the minimum SDK."
            jar.set(project.tasks.named("jar", Jar::class.java).flatMap { it.archiveFile })
            apiVersions.fileProvider(database.map { file -> file.takeIf { it.exists() } })
            apiVersionsPath.set(database.map { it.path }.orElse("platforms/android-$compileSdk/data/api-versions.xml"))
            minSdk.set(libs.findVersion("androidMinSdk").get().requiredVersion.toInt())
            report.set(project.layout.buildDirectory.file("reports/android-api.txt"))
        }
        project.tasks.named("check") { dependsOn(checkAndroidApi) }
    }

    /** The Android SDK directory, found as AGP finds it: `sdk.dir` in local.properties, then `ANDROID_HOME`. */
    private fun androidSdk(project: Project): Provider<File> {
        val localProperties = project.rootProject.layout.projectDirectory.file("local.properties")
        val fromLocalProperties = project.providers.fileContents(localProperties).asText.map { text ->
            Properties().apply { load(text.reader()) }.getProperty("sdk.dir")
        }
        return fromLocalProperties
            .orElse(project.providers.environmentVariable("ANDROID_HOME"))
            .orElse(project.providers.environmentVariable("ANDROID_SDK_ROOT"))
            .map(::File)
    }

    private fun configurePublishing(project: Project) {
        val publishing = project.extensions.getByType(PublishingExtension::class.java)
        // So a consumer or a license scanner sees each artifact's name, license and source location.
        publishing.publications.withType(MavenPublication::class.java).configureEach {
            pom {
                name.set(project.name)
                description.set(project.provider {
                    checkNotNull(project.description) { "${project.path} needs a `description` for its POM" }
                })
                url.set("https://github.com/Kotrace/kotrace")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                    }
                }
                developers {
                    developer {
                        id.set("kotrace")
                        name.set("Kotrace")
                    }
                }
                scm {
                    url.set("https://github.com/Kotrace/kotrace")
                    connection.set("scm:git:https://github.com/Kotrace/kotrace.git")
                    developerConnection.set("scm:git:ssh://git@github.com/Kotrace/kotrace.git")
                }
            }
        }
        // A local checkout of the static Maven repository behind https://maven.kotrace.dev (see gradle.properties).
        val repoDir = project.providers.gradleProperty("kotrace.maven.repo.dir").orNull ?: return
        publishing.repositories.maven {
            name = "KotraceMaven"
            url = File(repoDir).toURI()
        }
    }

    private val Project.kotlin: KotlinBaseExtension
        get() = extensions.getByName("kotlin") as KotlinBaseExtension

    /** The JVM and the Android Kotlin extensions both expose JVM compiler options. */
    @Suppress("UNCHECKED_CAST")
    private val KotlinBaseExtension.jvmCompilerOptions: KotlinJvmCompilerOptions
        get() = (this as HasConfigurableKotlinCompilerOptions<KotlinJvmCompilerOptions>).compilerOptions
}
