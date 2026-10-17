pluginManagement {
    // Build-only plugins, never published. Named apart from `build-logic`, which an app including kotrace in its own
    // composite build may already have.
    includeBuild("build-logic") { name = "kotrace-build-logic" }
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// The root project is the pure Kotlin core (`dev.kotrace:kotrace`). Each integration is its own module, so a
// consumer takes no HTTP client or Android dependency it does not use.
rootProject.name = "kotrace"

include(":kotrace-okhttp")
include(":kotrace-room")
// Bill of materials: aligns the versions of every published module.
include(":kotrace-bom")
// Unpublished: the JMH harness, kept apart so JMH never reaches a consumer, and the runnable showcase.
include(":benchmarks")
include(":demo")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
