// Build-only plugins for kotrace, never published. The root build includes it from `pluginManagement` and runs its
// tests from `check`.
dependencyResolutionManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "kotrace-build-logic"
