plugins {
    alias(libs.plugins.kotlin.jvm)
    // Declared apply-false so the Android module `:kotrace-room` resolves AGP from one known version.
    // AGP 9 has built-in Kotlin, so the Android module applies no separate Kotlin plugin.
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.dokka)
    `java-library`
    `maven-publish`
}

group = "dev.kotrace"
version = "0.2.0"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withSourcesJar()
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
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
