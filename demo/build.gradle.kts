plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// A runnable showcase only — not a library, not published (no `maven-publish`, no group/version).
dependencies {
    // Pulls the core (:) transitively, plus the okhttp module for the traceparent-on-the-wire demo.
    implementation(project(":kotrace-okhttp"))
    implementation(libs.okhttp)
}

application {
    mainClass = "dev.kotrace.demo.MainKt"
}
