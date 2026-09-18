package io.github.springconsole.reload

import io.github.springconsole.api.CompilationError

/**
 * Turns raw Gradle/Maven/javac/kotlinc output into line- and column-accurate
 * [CompilationError]s (FR-4.1): agents must never have to parse raw build
 * logs. The source file path is prefixed to the message, since the schema
 * carries only line/column/message.
 */
object BuildErrorParser {

    // kotlinc via Gradle: "e: file:///abs/Foo.kt:12:34 message"
    private val KOTLIN_URI = Regex("""^e:\s+file://(.+?):(\d+):(\d+)\s+(.*)$""")

    // kotlinc legacy: "e: /abs/Foo.kt: (12, 34): message"
    private val KOTLIN_PLAIN = Regex("""^e:\s+(.+?):\s*\((\d+),\s*(\d+)\):\s*(.*)$""")

    // javac via Gradle: "/abs/Foo.java:12: error: message"
    private val JAVAC = Regex("""^(.+?\.java):(\d+):\s*error:\s*(.*)$""")

    // javac via Maven: "[ERROR] /abs/Foo.java:[12,34] message"
    private val MAVEN_JAVAC = Regex("""^\[ERROR]\s+(.+?\.java):\[(\d+),(\d+)]\s*(.*)$""")

    fun parse(buildOutput: String): List<CompilationError> {
        val errors = mutableListOf<CompilationError>()
        val lines = buildOutput.lines()

        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()

            val positional = KOTLIN_URI.find(trimmed) ?: KOTLIN_PLAIN.find(trimmed) ?: MAVEN_JAVAC.find(trimmed)
            if (positional != null) {
                val (file, ln, col, message) = positional.destructured
                errors += error(file, ln, col, message)
                continue
            }

            val javac = JAVAC.find(trimmed)
            if (javac != null) {
                val (file, ln, message) = javac.destructured
                errors += error(file, ln, javacCaretColumn(lines, index).toString(), message)
            }
        }
        return errors.distinct()
    }

    /** javac marks the column with a caret on the line after the echoed source. */
    private fun javacCaretColumn(lines: List<String>, errorLineIndex: Int): Int {
        for (offset in 1..3) {
            val candidate = lines.getOrNull(errorLineIndex + offset) ?: break
            val caret = candidate.indexOf('^')
            if (caret >= 0 && candidate.substring(0, caret).isBlank()) return caret + 1
        }
        return 0
    }

    private fun error(file: String, line: String, column: String, message: String) = CompilationError(
        line = line.toIntOrNull() ?: -1,
        column = column.toIntOrNull() ?: -1,
        message = "${file.substringAfterLast('/')}: $message",
        severity = "ERROR",
    )
}
