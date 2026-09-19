package io.github.springconsole.repl

import io.github.springconsole.api.CompilationError
import io.github.springconsole.api.EvalResult
import io.github.springconsole.api.EvalStatus
import org.jline.utils.AttributedString
import org.jline.utils.AttributedStyle

/**
 * Turns an [EvalResult] into the colorized terminal view.
 *
 * This class owns the *presentation* half of the "nice view" acceptance
 * criterion:
 *
 * - the snippet's captured `stdout`/`stderr` is printed verbatim first, so
 *   `println` output and the returned value stay visually separate
 *   (determinism requirement: logging is isolated from return values),
 * - the returned value is rendered by [ResultRenderer] — a table for
 *   collections, a key/value block for objects, a single colored line for
 *   scalars — instead of the raw `toString()` the engine falls back to,
 * - compile errors are shown with line/column, the offending source line, and
 *   a colorized caret pointing at the column,
 * - runtime exceptions show the pruned type/message plus dimmed frames,
 * - the transactional sandbox state is reported as a dimmed footnote.
 *
 * It writes through [ReplOutput] and therefore works both on a real terminal
 * and on the in-memory dumb terminal used by the tests.
 */
class ReplPresenter(
    private val output: ReplOutput,
    private val renderer: ResultRenderer = ResultRenderer(),
) {

    /**
     * Renders [result]. [input] is the exact source text that was evaluated and
     * is used to draw the caret under a compile error.
     */
    fun present(result: EvalResult, input: String = "") {
        val printed = result.printedOutput
        if (printed.isNotEmpty()) {
            output.raw(printed)
            if (!printed.endsWith("\n")) output.raw("\n")
        }

        when (result.status) {
            EvalStatus.SUCCESS -> presentSuccess(result)
            EvalStatus.COMPILATION_ERROR -> presentCompilationErrors(input, result.compilationErrors.orEmpty())
            EvalStatus.RUNTIME_EXCEPTION -> presentException(result)
            EvalStatus.TIMEOUT -> output.error(result.result ?: "Evaluation timed out")
        }

        output.flush()
    }

    /**
     * Chooses what to show for a successful evaluation.
     *
     * The engine hands us both the raw value (for structured rendering) and a
     * rendered string (for the MCP JSON payload). The mapping is:
     *
     * | raw value        | rendered string | shown                       |
     * |------------------|-----------------|-----------------------------|
     * | `Unit`           | `null`          | nothing (statement)         |
     * | `null`           | `"null"`        | dim `null`                  |
     * | any object       | `toString()`    | structured, colorized view  |
     * | absent (no raw)  | text            | dim text fallback           |
     */
    private fun presentSuccess(result: EvalResult) {
        val raw = result.rawValue
        val lines: List<AttributedString> = when {
            raw != null -> renderer.render(raw)
            result.result == null -> emptyList()
            result.result == "null" -> renderer.render(null)
            else -> listOf(AttributedString(result.result.orEmpty(), ReplTheme.dim))
        }
        output.lines(lines)
    }

    /**
     * Prints each compiler diagnostic as `[line:column] message` followed by
     * the source line and a caret. The caret is the colour-coded equivalent of
     * a compiler's `^` marker and makes column-accurate errors (FR-4.1)
     * readable at a glance.
     */
    fun presentCompilationErrors(input: String, errors: List<CompilationError>) {
        if (errors.isEmpty()) {
            output.error("Compilation failed (no structured diagnostics available)")
            return
        }
        val sourceLines = input.split('\n')
        errors.forEach { error ->
            output.error("compile error [${error.line}:${error.column}] ${error.message}")
            val source = sourceLines.getOrNull(error.line - 1)
            if (source != null) {
                output.line("  $source", AttributedStyle.DEFAULT)
                if (error.column > 0) {
                    output.line("  " + " ".repeat(error.column - 1) + "^", ReplTheme.errorMarker)
                }
            }
        }
    }

    private fun presentException(result: EvalResult) {
        val exception = result.exception
        if (exception == null) {
            output.error(result.result ?: "Evaluation failed")
            return
        }
        output.error("${exception.type}: ${exception.message ?: "(no message)"}")
        exception.stackTrace.forEach { frame -> output.dim("  $frame") }
    }
}
