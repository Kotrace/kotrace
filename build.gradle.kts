plugins {
    alias(libs.plugins.kotlin.jvm)
    // Declared apply-false so the Android module `:kotrace-room` resolves AGP from one known version.
    // AGP 9 has built-in Kotlin, so the Android module applies no separate Kotlin plugin.
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.dokka)
    `java-library`
    `maven-publish`
    id("kotrace.release-guard")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withSourcesJar()
}

kotlin {
    // Binary-compatibility gate: `check` runs `checkKotlinAbi` against the committed `api/` dump, so a public
    // ABI change fails the build until it is reviewed and re-dumped with `updateKotlinAbi` — and a break
    // means a version bump (see README § Publishing).
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()
    // Consumer baseline (ADR-027): compile against the oldest Kotlin language/API the published artifact
    // supports, and declare that stdlib, rather than whatever this build's compiler bundles.
    coreLibrariesVersion = libs.versions.kotlinStdlibBaseline.get()
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
        val baseline = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.fromVersion(libs.versions.kotlinBaseline.get())
        languageVersion = baseline
        apiVersion = baseline
        // Pinned, not derived: below language 2.2 the default would drop interface default methods (abstract +
        // DefaultImpls only), silently changing the ABI and forcing Java implementors to override everything.
        jvmDefault = org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.ENABLE
    }
    // Tests may reset the install-once latch; production sources cannot (KotraceTestApi is ERROR-level).
    sourceSets.named("test") { languageSettings.optIn("dev.kotrace.KotraceTestApi") }
}

// API docs (Dokka v2). `@sample` references resolve against this samples root. The sample code lives in
// test source, so `check` compiles it against the real API — a sample that stops compiling breaks the
// build, which is the whole point: KDoc examples can no longer silently drift (D3 / see ADR-007).
dokka {
    // Warnings fail the doc build: a dangling KDoc link or an unresolved `@sample` reference is an error,
    // not a silent nit — this is what makes `dokkaGenerate` (run in CI) enforce D3.
    dokkaPublications.named("html") {
        failOnWarning.set(true)
    }
    dokkaSourceSets.named("main") {
        samples.from("src/test/kotlin/dev/kotrace/samples")
    }
}

dependencies {
    // coroutine types (SpanContext : ThreadContextElement, suspend trace) are part of the public API.
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
    // Publish target: a local checkout of the dedicated static Maven repo, served over GitHub Pages at
    // https://maven.kotrace.dev. Set `kotrace.maven.repo.dir` to that checkout (in ~/.gradle or via
    // -P), run `./gradlew publishAllPublicationsToKotraceMavenRepository`, then commit + push the repo.
    // Consumers pull anonymously — a static repo needs no credentials.
    val repoDir = providers.gradleProperty("kotrace.maven.repo.dir").orNull
    if (repoDir != null) {
        repositories {
            maven {
                name = "KotraceMaven"
                url = uri(File(repoDir).toURI())
            }
        }
    }
}

val pomDescriptions = mapOf(
    "kotrace" to "Coroutine-native tracing for Kotlin: span trees, tail-based failure reports, W3C traceparent.",
    "kotrace-okhttp" to "kotrace instrumentation for OkHttp: one span per call execution and traceparent injection.",
    "kotrace-room" to "kotrace instrumentation for Android Room: SQL statements as breadcrumbs on the active span.",
    "kotrace-bom" to "Bill of materials aligning the versions of every kotrace module.",
)

// Release guard (build-logic's `kotrace.release-guard`, README § Publishing): every remote publish, of every
// module and the BOM, first checks a clean tree, the `v<version>` tag and the Maven repo, and each POM records
// the commit it was built from. Its TestKit tests run from `check`.
tasks.check { dependsOn(gradle.includedBuild("kotrace-build-logic").task(":check")) }

allprojects {
    // One POM identity for every published module: a consumer (or a license scanner) resolving a kotrace
    // artifact gets its name, license and source location, not an anonymous jar.
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set(project.name)
                    description.set(pomDescriptions.getValue(project.name))
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
        }
    }
}
