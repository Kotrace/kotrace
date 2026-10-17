plugins {
    `java-platform`
    `maven-publish`
    id("kotrace.conventions")
}

description = "Bill of materials aligning the versions of every kotrace module."

// Constraints only, one per published module, so a consumer aligns kotrace versions without one per dependency.
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
}
