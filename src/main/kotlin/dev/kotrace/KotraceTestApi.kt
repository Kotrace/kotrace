package dev.kotrace

/**
 * Marks an API that exists **only for tests**, currently [Kotrace.resetForTest], which clears the
 * install-once latch that ADR-010 relies on. Called from production code, it would let a second `install`
 * silently replace a live sink, which is exactly what install-once exists to prevent.
 *
 * `ERROR` level: an un-opted call does not compile. Opt in for your **test source set only**, e.g. in Gradle:
 * `kotlin { sourceSets.test { languageSettings.optIn("dev.kotrace.KotraceTestApi") } }`, so production code
 * still cannot reach it.
 */
@RequiresOptIn(
    message = "Test-only kotrace API: it breaks the install-once contract (ADR-010). Opt in from a test source " +
        "set only, never from production code.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class KotraceTestApi
