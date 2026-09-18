package io.github.springconsole.engine

/**
 * Holds the current [KotlinReplEngine], creating it lazily (compiler
 * initialization is expensive) and allowing a full reset after a context
 * reload so stale snippet classes referencing the old ClassLoader are purged
 * (FR-3.5).
 */
class EngineHolder(@Volatile private var factory: () -> KotlinReplEngine) {

    @Volatile
    private var current: KotlinReplEngine? = null

    fun engine(): KotlinReplEngine {
        current?.let { return it }
        synchronized(this) {
            current?.let { return it }
            return factory().also { current = it }
        }
    }

    /** Discards the engine (and all snippet state). A new one is built on next use. */
    fun reset(newFactory: (() -> KotlinReplEngine)? = null) {
        synchronized(this) {
            if (newFactory != null) {
                factory = newFactory
            }
            current = null
        }
    }

    /** Eagerly initializes the engine off the caller's thread budget. */
    fun warmUp() {
        engine().eval("0")
    }
}
