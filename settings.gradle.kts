pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "kotrace"

// The root project is the pure-Kotlin tracer core (`dev.kotrace:kotrace`). `:kotrace-okhttp` is the
// OkHttp instrumentation module — kept separate so the core drags no HTTP client (OTel api/instrumentation
// split). A Ktor or backend consumer takes only the core.
include(":kotrace-okhttp")

// `:kotrace-room` is the Room instrumentation module — Android, so kept out of the pure-JVM core (same
// split as `:kotrace-okhttp`). A pure-JVM or okhttp-only consumer never drags androidx.room.
include(":kotrace-room")

// `:kotrace-bom` is the Bill of Materials — a constraints-only `java-platform` pinning one coherent set
// of kotrace module versions, so consumers align them without a version on each dependency.
include(":kotrace-bom")

// `:demo` is a runnable showcase, not a published artifact — nested/parallel tracing and the OkHttp
// `traceparent` glue, driven end-to-end against a throwaway in-process server. Never published.
include(":demo")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
