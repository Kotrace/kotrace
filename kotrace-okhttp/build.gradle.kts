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
    // Both are in the public API: TracingInterceptor is an okhttp Interceptor, TracingCallFactory wraps
    // a Call.Factory, and Span (from the core) is on their surfaces.
    api(project(":"))
    api(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
    // Same static-Maven-repo target as the core (see root build.gradle.kts / README).
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