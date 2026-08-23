plugins {
    `java-platform`
    `maven-publish`
}

group = "dev.kotrace"
version = "0.2.1"

// The BOM pins one coherent set of kotrace versions so consumers declare them without a version each.
// It carries constraints only — every published module, never `:demo` (unpublished) and never itself.
dependencies {
    constraints {
        api(project(":"))
        api(project(":kotrace-okhttp"))
        api(project(":kotrace-room"))
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["javaPlatform"])
        }
    }
    // Same static-Maven-repo target as the core (see root build.gradle.kts / README).
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
