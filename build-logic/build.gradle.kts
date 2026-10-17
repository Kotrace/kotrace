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
        register("conventions") {
            id = "kotrace.conventions"
            implementationClass = "dev.kotrace.buildlogic.ConventionsPlugin"
        }
    }
}

dependencies {
    // Provided at runtime: AGP, KGP and Dokka by the build that applies the plugin, abi-tools by an isolated worker
    // classpath.
    compileOnly(libs.android.gradle.api)
    compileOnly(libs.kotlin.gradle.plugin.api)
    compileOnly(libs.dokka.gradle.plugin)
    compileOnly(libs.kotlin.abi.tools.api)
    // `kotlin-dsl` applies `java-gradle-plugin`, which adds TestKit and the plugin-under-test metadata.
    testImplementation(libs.junit)
}
