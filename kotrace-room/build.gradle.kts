plugins {
    // AGP 9 provides built-in Kotlin — no separate kotlin plugin (applying it is a hard error).
    alias(libs.plugins.android.library)
    alias(libs.plugins.dokka)
    `maven-publish`
    // The ABI gate the JVM modules get from `abiValidation()`, which dumps nothing on AGP's built-in Kotlin.
    id("kotrace.android-abi")
}

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

// API docs for this module (`./gradlew dokkaGenerate` runs it with the core's); warnings fail, as in the core.
dokka {
    dokkaPublications.named("html") { failOnWarning.set(true) }
}

kotlin {
    // Same consumer baseline as the core (ADR-027; see root build.gradle.kts).
    coreLibrariesVersion = libs.versions.kotlinStdlibBaseline.get()
    compilerOptions {
        val baseline = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.fromVersion(libs.versions.kotlinBaseline.get())
        languageVersion = baseline
        apiVersion = baseline
        // Pinned, not derived: below language 2.2 the default would drop interface default methods (abstract +
        // DefaultImpls only), silently changing the ABI and forcing Java implementors to override everything.
        jvmDefault = org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.ENABLE
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
