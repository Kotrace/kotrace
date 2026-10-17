plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    `java-library`
    `maven-publish`
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withSourcesJar()
}

kotlin {
    // Same binary-compatibility gate as the core (see root build.gradle.kts).
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()
    // Consumer baseline (ADR-027): compile against the oldest Kotlin language/API the published artifact
    // supports, and declare that stdlib, rather than whatever this build's compiler bundles.
    coreLibrariesVersion = libs.versions.kotlinStdlibBaseline.get()
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
        val baseline = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.fromVersion(libs.versions.kotlinBaseline.get())
        languageVersion = baseline
        apiVersion = baseline
        // Pinned, not derived: below language 2.2 the default would drop interface default methods (abstract +
        // DefaultImpls only), silently changing the ABI and forcing Java implementors to override everything.
        jvmDefault = org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.ENABLE
    }
    // Tests may reset the install-once latch; production sources cannot (KotraceTestApi is ERROR-level).
    sourceSets.named("test") { languageSettings.optIn("dev.kotrace.KotraceTestApi") }
}

// API docs for this module (`./gradlew dokkaGenerate` runs it with the core's); warnings fail, as in the core.
dokka {
    dokkaPublications.named("html") { failOnWarning.set(true) }
}

dependencies {
    // Both are in the public API: TracingInterceptor is an okhttp Interceptor, TracingCallFactory wraps
    // a Call.Factory, and Span (from the core) is on their surfaces.
    api(project(":"))
    api(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp.mockwebserver)
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
                name = "KotraceMaven"
                url = uri(File(repoDir).toURI())
            }
        }
    }
}
// The module compiles and tests against its OkHttp 4.12 floor (ADR-027), and the README promises 5.x works.
// testOkHttp5 reruns the same compiled tests with every OkHttp artifact at 5.x on the runtime classpath, the
// way a consumer on OkHttp 5 runs this jar; `check` runs both.
val okhttp5TestRuntimeClasspath = configurations.resolvable("okhttp5TestRuntimeClasspath") {
    extendsFrom(configurations.testImplementation.get(), configurations.testRuntimeOnly.get())
    attributes.addAllLater(configurations.testRuntimeClasspath.get().attributes)
    resolutionStrategy.eachDependency {
        if (requested.group == "com.squareup.okhttp3") {
            useVersion(libs.versions.okhttp5Test.get())
            because("run the tests against the newest OkHttp major, not only the floor")
        }
    }
}

val testOkHttp5 by tasks.registering(Test::class) {
    description = "Runs the unit tests, compiled against OkHttp 4.12, on an OkHttp 5 runtime."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().output + sourceSets.main.get().output + files(okhttp5TestRuntimeClasspath)
    val okhttp5 = libs.versions.okhttp5Test.get()
    val runtime = files(okhttp5TestRuntimeClasspath)
    doFirst {
        // Guard the guard: a resolution change that silently kept 4.x would make this task a second copy of `test`.
        check(runtime.any { it.name.startsWith("okhttp-") && it.name.contains(okhttp5) }) {
            "testOkHttp5 did not resolve OkHttp $okhttp5: ${runtime.filter { it.name.startsWith("okhttp") }.files}"
        }
    }
}
tasks.check { dependsOn(testOkHttp5) }
