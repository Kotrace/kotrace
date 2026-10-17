plugins {
    alias(libs.plugins.kotlin.jvm)
    // Declared here so `:kotrace-room` resolves one AGP version. AGP 9 has built-in Kotlin, so that module applies
    // no Kotlin plugin.
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.dokka)
    `java-library`
    `maven-publish`
    // Java 11, the consumer baseline, the ABI gate, the opt-ins, Dokka and the POM, shared by every published module.
    id("kotrace.conventions")
    id("kotrace.release-guard")
}

description = "Coroutine-native tracing for Kotlin: span trees, tail-based failure reports, W3C traceparent."

kotlin {
    // KDoc `@sample` sources compile with `main` (ADR-007): the IDE resolves `@sample` from `main`'s scope, not from
    // Dokka's samples root, and a sample that stops compiling breaks the build. Every sample is `internal`, so none
    // reaches the ABI dump, and the package is kept out of both jars and the API documentation.
    sourceSets.named("main") { kotlin.srcDir("src/samples/kotlin") }
}

tasks.named<Jar>("jar") { exclude("dev/kotrace/samples/**") }
tasks.named<Jar>("sourcesJar") { exclude("dev/kotrace/samples/**") }

dokka {
    dokkaSourceSets.named("main") {
        samples.from("src/samples/kotlin")
        perPackageOption {
            matchingRegex.set("""dev\.kotrace\.samples""")
            suppress.set(true)
        }
    }
}

dependencies {
    // Coroutine types are on the public API: `SpanContext` is a `ThreadContextElement`, `span` is suspend.
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
}

// The release guard (`kotrace.release-guard`, README § Publishing) checks a clean tree, the `v<version>` tag and
// the Maven repository before every remote publish, and records the commit in each POM. `check` runs its tests.
tasks.check { dependsOn(gradle.includedBuild("kotrace-build-logic").task(":check")) }
