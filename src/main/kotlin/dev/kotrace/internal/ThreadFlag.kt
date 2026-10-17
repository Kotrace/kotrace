package dev.kotrace.internal

/**
 * A per-thread flag, false until set. Not `ThreadLocal.withInitial`, which Android has only from API 26: the core
 * runs on Android apps below it (README, Minimum versions).
 */
internal fun threadFlag(): ThreadLocal<Boolean> = object : ThreadLocal<Boolean>() {
    override fun initialValue(): Boolean = false
}
