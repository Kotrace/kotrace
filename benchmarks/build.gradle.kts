plugins {
    alias(libs.plugins.kotlin.jvm)
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

dependencies {
    implementation(project(":"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jmh.core)
    annotationProcessor(libs.jmh.generator.annprocess)

    testImplementation(libs.junit)
}

tasks.register<JavaExec>("jmh") {
    group = "benchmark"
    description = "Runs JMH; pass whitespace-separated CLI flags with -PjmhArgs='...'"
    dependsOn(tasks.named("classes"))
    mainClass.set("dev.kotrace.benchmarks.BenchmarkMain")
    classpath = sourceSets.main.get().runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
            vendor.set(JvmVendorSpec.ORACLE)
        },
    )
    workingDir = rootProject.projectDir
    jvmArgs("-Xms1g", "-Xmx1g", "-XX:+UseG1GC", "-Dfile.encoding=UTF-8")
    doFirst {
        args = providers.gradleProperty("jmhArgs").orNull
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.split(Regex("\\s+"))
            .orEmpty()
    }
}
