plugins {
    // AGP 9 has built-in Kotlin: applying the Kotlin plugin as well is an error.
    alias(libs.plugins.android.library)
    alias(libs.plugins.dokka)
    // Generates the tests' Room database only: nothing it generates reaches the published artifact.
    alias(libs.plugins.ksp)
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

ksp {
    // Kotlin, not Java: Room's Java output for a suspend DAO does not compile against Kotlin 2's signatures.
    arg("room.generateKotlin", "true")
}

dependencies {
    // Core types are on the callback's surface, so the core is `api`. Room is `compileOnly`: the consumer brings
    // its own room-runtime, and this module must not pin its version.
    api(project(":"))
    compileOnly(libs.room.runtime)
    // Room's default query executor, which `tracing()` wraps when the app passes none. Room brings it at run time.
    compileOnly(libs.arch.core.runtime)

    testImplementation(libs.room.runtime)
    // A real in-memory database under Robolectric, at the same Room floor, for what a mock cannot show: that Room
    // calls the callback inline on its query thread, under the caller's mirrored span.
    testImplementation(libs.room.ktx)
    kspTest(libs.room.compiler)
    testImplementation(libs.robolectric)
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
