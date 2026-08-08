rootProject.name = "kotrace"

// The root project is the pure-Kotlin tracer core (`dev.luxgroup:kotrace`). `:kotrace-okhttp` is the
// OkHttp instrumentation module — kept separate so the core drags no HTTP client (OTel api/instrumentation
// split). A Ktor or backend consumer takes only the core.
include(":kotrace-okhttp")

// `:demo` is a runnable showcase, not a published artifact — nested/parallel tracing and the OkHttp
// `traceparent` glue, driven end-to-end against a throwaway in-process server. Never published.
include(":demo")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}
