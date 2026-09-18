package io.github.springconsole.engine

import io.github.springconsole.api.ExceptionDetails

/**
 * Reduces runtime exceptions to their domain-relevant frames (FR-4.2):
 * Spring framework internals, reflection delegates, and console/scripting
 * machinery are stripped to minimize token consumption for agents, while
 * root-cause domain frames are retained.
 */
object StackTracePruner {

    private val HIDDEN_PACKAGE_PREFIXES = listOf(
        "org.springframework.aop.",
        "org.springframework.cglib.",
        "org.springframework.transaction.interceptor.",
        "org.springframework.dao.support.",
        "org.springframework.data.repository.core.support.",
        "org.springframework.data.projection.",
        "jdk.internal.reflect.",
        "java.lang.reflect.",
        "sun.reflect.",
        "kotlin.script.",
        "kotlin.coroutines.",
        "kotlinx.coroutines.",
        "org.jetbrains.kotlin.",
        "io.github.springconsole.",
        "java.util.concurrent.",
        "jdk.proxy",
    )

    /** Snippet classes are always kept — they point at the failing script line. */
    private const val SNIPPET_CLASS_PREFIX = "Snippet_"

    fun details(throwable: Throwable, maxFrames: Int = 10): ExceptionDetails {
        val frames = mutableListOf<String>()
        frames += prunedFrames(throwable, maxFrames)

        var cause = throwable.cause
        var depth = 0
        while (cause != null && cause !== cause.cause && depth < 5) {
            frames += "Caused by: ${cause.javaClass.name}: ${cause.message}"
            frames += prunedFrames(cause, 3)
            cause = cause.cause
            depth++
        }

        return ExceptionDetails(
            type = throwable.javaClass.name,
            message = throwable.message,
            stackTrace = frames,
        )
    }

    private fun prunedFrames(throwable: Throwable, maxFrames: Int): List<String> =
        throwable.stackTrace
            .filter { frame ->
                frame.className.substringAfterLast('.').startsWith(SNIPPET_CLASS_PREFIX) ||
                    HIDDEN_PACKAGE_PREFIXES.none { frame.className.startsWith(it) }
            }
            .take(maxFrames)
            .map { "at $it" }

    /** The innermost cause, useful for compact one-line rendering. */
    fun rootCause(throwable: Throwable): Throwable {
        var current = throwable
        while (current.cause != null && current.cause !== current) {
            current = current.cause!!
        }
        return current
    }
}
