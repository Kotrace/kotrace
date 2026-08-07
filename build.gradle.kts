plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

group = "dev.luxgroup"
version = "0.1.0"

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
    // https://maven.luxgroup.dev. Set `kotrace.maven.repo.dir` to that checkout (in ~/.gradle or via
    // -P), run `./gradlew publishAllPublicationsToLuxGroupMavenRepository`, then commit + push the repo.
    // Consumers pull anonymously — a static repo needs no credentials.
    val repoDir = providers.gradleProperty("kotrace.maven.repo.dir").orNull
    if (repoDir != null) {
        repositories {
            maven {
                name = "LuxGroupMaven"
                url = uri(File(repoDir).toURI())
            }
        }
    }
}
