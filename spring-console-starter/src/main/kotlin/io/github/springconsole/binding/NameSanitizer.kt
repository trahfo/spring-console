package io.github.springconsole.binding

/**
 * Deterministically maps Spring bean names to valid Kotlin identifiers
 * (FR-1.3). Rules, applied in order:
 *
 * 1. every character outside `[A-Za-z0-9_]` becomes `_`
 * 2. a leading digit is prefixed with `_`
 * 3. Kotlin hard keywords get a trailing `_` (`class` → `class_`)
 * 4. collisions get a numeric suffix in the caller's iteration order
 *    (`_2`, `_3`, …) — callers must iterate beans in a stable order.
 */
object NameSanitizer {

    private val HARD_KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun",
        "if", "in", "interface", "is", "null", "object", "package", "return",
        "super", "this", "throw", "true", "try", "typealias", "typeof", "val",
        "var", "when", "while",
    )

    fun sanitize(name: String): String {
        val cleaned = name.map { if (it.isLetterOrDigit() && it.code < 128 || it == '_') it else '_' }
            .joinToString("")
            .ifEmpty { "_" }
        val prefixed = if (cleaned.first().isDigit()) "_$cleaned" else cleaned
        return if (prefixed in HARD_KEYWORDS) "${prefixed}_" else prefixed
    }

    /** Sanitizes [name], appending `_2`, `_3`, … until it is absent from [taken]. */
    fun sanitizeUnique(name: String, taken: Set<String>): String {
        val base = sanitize(name)
        if (base !in taken) return base
        var suffix = 2
        while ("${base}_$suffix" in taken) {
            suffix++
        }
        return "${base}_$suffix"
    }
}
