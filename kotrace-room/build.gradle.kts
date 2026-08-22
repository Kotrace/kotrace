plugins {
    // AGP 9 provides built-in Kotlin — no separate kotlin plugin (applying it is a hard error).
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = "dev.kotrace"
version = "0.2.0"

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
    // Span (from the core) is on the callback's surface, so it is api. Room is compileOnly: the consumer
    // already brings room-runtime, and this module must not pin their version.
    api(project(":"))
    compileOnly(libs.room.runtime)

    testImplementation(libs.room.runtime)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
        }
    }
    val repoDir = providers.gradleProperty("kotrace.maven.repo.dir").orNull
    if (repoDir != null) {
        repositories {
            maven {
                name = "KotraceMaven"
                url = uri(File(repoDir).toURI())
            }
        }
    }
}
