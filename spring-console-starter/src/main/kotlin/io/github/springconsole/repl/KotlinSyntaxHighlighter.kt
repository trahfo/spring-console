package io.github.springconsole.repl

import org.jline.reader.Highlighter
import org.jline.reader.LineReader
import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder
import org.jline.utils.AttributedStyle
import java.util.regex.Pattern

/**
 * Kotlin syntax highlighting for the interactive REPL, implemented as a JLine3
 * [Highlighter] — the established extension point the line editor uses to
 * style the current input buffer on every redraw.
 *
 * ## How it works
 *
 * 1. [KotlinLexer] splits the buffer into typed tokens (cheap, total, never
 *    throws — see its documentation for why a hand-written lexer is used).
 * 2. Each [TokenType] is mapped to a [ReplTheme] style.
 * 3. If the line editor reported a problem position — JLine calls
 *    [setErrorIndex] or [setErrorPattern] when the application attached a
 *    [org.jline.reader.Parser] error — that range is painted with
 *    [ReplTheme.errorMarker] on top, so the user sees exactly which characters
 *    the compiler rejected.
 *
 * The highlighter is stateless apart from the two mutable error fields, which
 * JLine sets from the editing thread and this class reads from the same
 * thread; they are marked `@Volatile` anyway so the class stays safe if a
 * future integration updates them from elsewhere.
 *
 * ## Example
 *
 * ```
 * val greeting = "hello"   // val: magenta bold, "hello": green, comment: gray italic
 * todoService.create(req)  // identifiers plain, punctuation plain
 * 42.0f                    // yellow
 * ```
 */
class KotlinSyntaxHighlighter(
    private val lexer: KotlinLexer = KotlinLexer(),
) : Highlighter {

    @Volatile
    private var errorPattern: Pattern? = null

    @Volatile
    private var errorIndex: Int = -1

    /**
     * Called by JLine when a parser error should be highlighted by matching a
     * regular expression against the buffer. The first match is highlighted.
     */
    override fun setErrorPattern(errorPattern: Pattern?) {
        this.errorPattern = errorPattern
    }

    /**
     * Called by JLine when a parser error should be highlighted at a single
     * buffer position. Must be `>= 0` to take effect; `-1` clears the marker.
     */
    override fun setErrorIndex(errorIndex: Int) {
        this.errorIndex = errorIndex
    }

    /**
     * Styles [buffer]. Always returns a string of the same length as [buffer],
     * which is a hard requirement of the JLine editing contract (a length
     * mismatch would corrupt the cursor math).
     *
     * [reader] is accepted as nullable because the highlighter never needs the
     * surrounding reader; tests call it directly with `null`.
     */
    override fun highlight(reader: LineReader?, buffer: String): AttributedString {
        val errorRange = errorRange(buffer)
        val builder = AttributedStringBuilder(buffer.length)
        for (token in lexer.tokenize(buffer)) {
            appendStyled(builder, buffer, token.start, token.end, styleFor(token.type), errorRange)
        }
        // Defensive: if the lexer ever returned a gap (it should not), append
        // the remainder unstyled so the result still matches the buffer.
        if (builder.length < buffer.length) {
            builder.append(buffer, builder.length, buffer.length)
        }
        return builder.toAttributedString()
    }

    /** Maps a lexical token to its palette style. */
    private fun styleFor(type: TokenType): AttributedStyle = when (type) {
        TokenType.WHITESPACE -> AttributedStyle.DEFAULT
        TokenType.LINE_COMMENT, TokenType.BLOCK_COMMENT -> ReplTheme.comment
        TokenType.STRING, TokenType.CHAR -> ReplTheme.string
        TokenType.NUMBER -> ReplTheme.number
        TokenType.KEYWORD -> ReplTheme.keyword
        TokenType.SOFT_KEYWORD -> ReplTheme.softKeyword
        TokenType.LITERAL -> ReplTheme.literal
        TokenType.ANNOTATION -> ReplTheme.annotation
        TokenType.BACKTICKED -> ReplTheme.backticked
        TokenType.IDENTIFIER -> ReplTheme.identifier
        TokenType.PUNCTUATION -> AttributedStyle.DEFAULT
    }

    /**
     * Resolves the buffer range that should be marked as erroneous.
     *
     * - A configured [errorPattern] wins and contributes its **first** match
     *   (mirroring JLine's own `DefaultHighlighter` semantics).
     * - Otherwise a valid [errorIndex] marks that single character.
     * - Empty matches are ignored so the error never disappears silently.
     */
    private fun errorRange(buffer: String): IntRange? {
        val pattern = errorPattern
        if (pattern != null) {
            val matcher = pattern.matcher(buffer)
            if (matcher.find() && matcher.end() > matcher.start()) {
                return matcher.start() until matcher.end()
            }
        }
        val index = errorIndex
        if (index in buffer.indices) return index until (index + 1)
        return null
    }

    /**
     * Appends `buffer[start, end)` with [base] style, splitting the range at
     * the borders of [errorRange] so the overlapping characters get the error
     * style instead. Splitting (rather than a second styling pass) keeps the
     * first-match semantics of [errorRange] intact.
     */
    private fun appendStyled(
        builder: AttributedStringBuilder,
        buffer: String,
        start: Int,
        end: Int,
        base: AttributedStyle,
        errorRange: IntRange?,
    ) {
        if (end <= start) return
        if (errorRange == null || errorRange.last < start || errorRange.first >= end) {
            builder.style(base).append(buffer, start, end)
            return
        }
        var position = start
        val errorStart = maxOf(start, errorRange.first)
        if (position < errorStart) {
            builder.style(base).append(buffer, position, errorStart)
            position = errorStart
        }
        val errorEnd = minOf(end, errorRange.last + 1)
        builder.style(ReplTheme.errorMarker).append(buffer, position, errorEnd)
        position = errorEnd
        if (position < end) {
            builder.style(base).append(buffer, position, end)
        }
    }
}
