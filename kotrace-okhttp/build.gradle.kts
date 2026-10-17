plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    `java-library`
    `maven-publish`
    id("kotrace.conventions")
}

description = "kotrace instrumentation for OkHttp: one span per call execution and traceparent injection."

dependencies {
    // Both are on the public API: `TracingInterceptor` is an OkHttp `Interceptor`, `TracingCallFactory` wraps a
    // `Call.Factory`, and core types are on their surfaces.
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
}

// The module compiles and tests against its OkHttp 4.12 floor (ADR-027), and the README promises 5.x works.
// `testOkHttp5` reruns the same compiled tests with OkHttp 5 on the runtime classpath, as a consumer on OkHttp 5
// runs this jar; `check` runs both.
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
        // A resolution that silently kept 4.x would make this task a copy of `test`.
        check(runtime.any { it.name.startsWith("okhttp-") && it.name.contains(okhttp5) }) {
            "testOkHttp5 did not resolve OkHttp $okhttp5: ${runtime.filter { it.name.startsWith("okhttp") }.files}"
        }
    }
}
tasks.check { dependsOn(testOkHttp5) }
