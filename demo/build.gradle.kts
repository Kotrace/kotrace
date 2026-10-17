plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// A runnable showcase, never published.
dependencies {
    // The core comes through the OkHttp module.
    implementation(project(":kotrace-okhttp"))
    implementation(libs.okhttp)
}

application {
    mainClass = "dev.kotrace.demo.MainKt"
}
