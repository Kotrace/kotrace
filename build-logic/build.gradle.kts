plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        register("releaseGuard") {
            id = "kotrace.release-guard"
            implementationClass = "dev.kotrace.buildlogic.ReleaseGuardPlugin"
        }
        register("androidAbi") {
            id = "kotrace.android-abi"
            implementationClass = "dev.kotrace.buildlogic.AndroidAbiPlugin"
        }
    }
}

dependencies {
    // Provided at runtime: AGP by the build applying the plugin, abi-tools by an isolated worker classpath.
    compileOnly(libs.android.gradle.api)
    compileOnly(libs.kotlin.abi.tools.api)
    // java-gradle-plugin (applied by kotlin-dsl) adds gradleTestKit() and the plugin-under-test metadata.
    testImplementation(libs.junit)
}
