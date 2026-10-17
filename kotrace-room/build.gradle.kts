plugins {
    // AGP 9 has built-in Kotlin: applying the Kotlin plugin as well is an error.
    alias(libs.plugins.android.library)
    alias(libs.plugins.dokka)
    `maven-publish`
    id("kotrace.conventions")
    // The ABI gate: `abiValidation()` dumps nothing on AGP's built-in Kotlin.
    id("kotrace.android-abi")
}

description = "kotrace instrumentation for Android Room: SQL statements as breadcrumbs on the active span."

android {
    namespace = "dev.kotrace.room"
    compileSdk {
        version = release(libs.versions.androidCompileSdk.get().toInt())
    }

    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

dependencies {
    // Core types are on the callback's surface, so the core is `api`. Room is `compileOnly`: the consumer brings
    // its own room-runtime, and this module must not pin its version.
    api(project(":"))
    compileOnly(libs.room.runtime)

    testImplementation(libs.room.runtime)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
        }
    }
}
