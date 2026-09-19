package io.github.springconsole.repl

import org.jline.terminal.Terminal
import org.jline.utils.AttributedString
import org.jline.utils.AttributedStyle

/**
 * Thin, testable wrapper around a JLine [Terminal] writer.
 *
 * Every piece of console output goes through here so that:
 *
 * - color is applied consistently from [ReplTheme],
 * - JLine can downgrade colors for terminals that do not support them
 *   (`AttributedString.toAnsi(terminal)` honors the terminal's capability
 *   report, and prints plain text on a dumb terminal),
 * - tests can drive the REPL with an in-memory dumb terminal and assert on the
 *   resulting text without needing a real TTY.
 */
class ReplOutput(private val terminal: Terminal) {

    /** Prints [message] on its own line in [style]. */
    fun line(message: CharSequence, style: AttributedStyle = AttributedStyle.DEFAULT) {
        terminal.writer().println(AttributedString(message.toString(), style).toAnsi(terminal))
        terminal.flush()
    }

    /** Prints pre-styled lines (as produced by [ResultRenderer]). */
    fun lines(lines: List<AttributedString>) {
        lines.forEach { terminal.writer().println(it.toAnsi(terminal)) }
        terminal.flush()
    }

    /** Writes already-formatted text verbatim (e.g. captured snippet stdout). */
    fun raw(text: String) {
        terminal.writer().print(text)
        terminal.flush()
    }

    fun flush() {
        terminal.flush()
    }

    /** Convenience shorthands used throughout the REPL. */
    fun success(message: CharSequence) = line(message, ReplTheme.success)
    fun info(message: CharSequence) = line(message, ReplTheme.info)
    fun error(message: CharSequence) = line(message, ReplTheme.error)
    fun warning(message: CharSequence) = line(message, ReplTheme.warning)
    fun dim(message: CharSequence) = line(message, ReplTheme.dim)
}
