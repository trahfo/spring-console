package io.github.springconsole.repl

import org.jline.reader.Candidate
import org.jline.reader.ParsedLine
import org.jline.terminal.Terminal
import org.jline.terminal.impl.DumbTerminal
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * In-memory JLine terminal used by the REPL tests.
 *
 * `DumbTerminal` is a real [Terminal] that writes to a byte array and reports
 * no color support, so:
 *
 * - `ReplOutput` / `ReplPresenter` / `ConsoleRepl` can be exercised exactly as
 *   in production,
 * - the captured text is plain (no escape sequences), which makes assertions
 *   readable,
 * - no TTY or pseudo-terminal is required (constructing a `LineReader` on a
 *   captured terminal would fail on macOS without a real window size).
 *
 * @param input scripted keystrokes; `ConsoleRepl` reads them with a real JLine
 *   `LineReader`, so `\t` triggers completion and `\n` submits a line.
 */
class CapturingTerminal(input: String = "") {
    private val sink = ByteArrayOutputStream()

    val terminal: Terminal = DumbTerminal(ByteArrayInputStream(input.toByteArray()), sink)

    fun text(): String = sink.toString(StandardCharsets.UTF_8)

    fun lines(): List<String> = text().split('\n').dropLastWhile { it.isEmpty() }

    fun output(): ReplOutput = ReplOutput(terminal)
}

/**
 * A [ParsedLine] that mirrors JLine's default whitespace-based word semantics:
 * [word] is the whitespace-delimited token containing the cursor and
 * [wordCursor] is the offset of the cursor inside it.
 */
class FakeParsedLine(
    private val text: String,
    private val cursorPosition: Int = text.length,
) : ParsedLine {

    override fun word(): String {
        val (start, end) = bounds()
        return text.substring(start, end)
    }

    override fun wordCursor(): Int {
        val (start, _) = bounds()
        return (cursorPosition - start).coerceAtLeast(0)
    }

    override fun wordIndex(): Int = words().indexOf(word())

    override fun words(): List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    override fun line(): String = text

    override fun cursor(): Int = cursorPosition

    private fun bounds(): Pair<Int, Int> {
        val cursor = cursorPosition.coerceIn(0, text.length)
        var start = cursor
        while (start > 0 && !text[start - 1].isWhitespace()) start--
        var end = cursor
        while (end < text.length && !text[end].isWhitespace()) end++
        return start to end
    }
}

/** Runs [ConsoleCompleter] over [text] and returns the raw candidate list. */
fun complete(completer: ConsoleCompleter, text: String, cursor: Int = text.length): List<Candidate> {
    val candidates = mutableListOf<Candidate>()
    completer.complete(null, FakeParsedLine(text, cursor), candidates)
    return candidates
}

/** Convenience: the `value` strings of the candidates for [text]. */
fun completedValues(completer: ConsoleCompleter, text: String, cursor: Int = text.length): List<String> =
    complete(completer, text, cursor).map { it.value() }

/** Convenience: the `displ` display strings of the candidates for [text]. */
fun completedDisplays(completer: ConsoleCompleter, text: String, cursor: Int = text.length): List<String> =
    complete(completer, text, cursor).map { it.displ() }
