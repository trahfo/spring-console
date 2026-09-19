package io.github.springconsole.repl

/**
 * Lexical categories produced by [KotlinLexer]. Each category maps to exactly
 * one [ReplTheme] style in [KotlinSyntaxHighlighter], which keeps the "what is
 * this token" decision (lexer) separate from the "what color is it" decision
 * (highlighter).
 */
enum class TokenType {
    WHITESPACE,
    LINE_COMMENT,
    BLOCK_COMMENT,
    STRING,
    CHAR,
    NUMBER,
    /** Kotlin hard keyword, e.g. `fun`, `val`, `return`. */
    KEYWORD,
    /** Kotlin soft keyword/modifier, e.g. `data`, `suspend`, `private`. */
    SOFT_KEYWORD,
    /** `true`, `false` or `null`. */
    LITERAL,
    /** `@Something` annotation usage. */
    ANNOTATION,
    /** A backticked identifier such as `` `my bean` ``. */
    BACKTICKED,
    IDENTIFIER,
    PUNCTUATION,
}

/**
 * A half-open `[start, end)` range into the lexed buffer together with its
 * [TokenType]. Tokens never overlap and, for a successful tokenization, tile
 * the whole input (see [KotlinLexer.tokenize]).
 */
data class KotlinToken(val type: TokenType, val start: Int, val end: Int) {
    init {
        require(start >= 0) { "start must be >= 0 but was $start" }
        require(end >= start) { "end ($end) must be >= start ($start)" }
    }

    val length: Int get() = end - start
}

/**
 * A small, dependency-free Kotlin **lexer** (not a parser) used purely for
 * syntax highlighting of the REPL input line.
 *
 * ### Why hand-written?
 *
 * The Kotlin compiler's own lexer is available on the classpath, but invoking
 * it on every keystroke is far too heavy and can throw on incomplete input
 * (which is the *normal* state while typing). A highlighter must be:
 *
 * - **fast** — it runs on every buffer change,
 * - **total** — it must return tokens for *any* string, including an
 *   unterminated string literal or an unterminated block comment,
 * - **forgiving** — never throwing is more important than perfect fidelity.
 *
 * The lexer therefore never fails: every character of the input ends up inside
 * exactly one token, and unterminated constructs simply extend to the end of
 * the buffer. That makes the result safe to feed straight into an
 * `AttributedStringBuilder`.
 *
 * ### What it recognizes
 *
 * - whitespace runs (including newlines),
 * - `//` line comments and nestable block comments,
 * - `"…"` strings with backslash escapes, and `"""…"""` raw strings,
 * - `'…'` character literals,
 * - numbers: decimal, `_` separators, fractions, exponents, `0x`/`0b` radixes
 *   and `L`/`F`/`D`/`U` suffixes,
 * - `true` / `false` / `null`,
 * - `@Annotation` usages,
 * - backticked identifiers,
 * - Kotlin hard keywords, soft keywords/modifiers, and plain identifiers,
 * - any other single character as [TokenType.PUNCTUATION].
 */
class KotlinLexer {

    /**
     * Splits [text] into a list of adjacent [KotlinToken]s. The concatenation
     * of all token ranges is exactly `0 until text.length`, so a highlighter
     * can append tokens in order without tracking gaps.
     */
    fun tokenize(text: String): List<KotlinToken> {
        if (text.isEmpty()) return emptyList()
        val tokens = ArrayList<KotlinToken>(text.length / 2 + 1)
        var i = 0
        while (i < text.length) {
            val start = i
            val c = text[i]
            i = when {
                c.isWhitespace() -> consumeWhile(text, i) { it.isWhitespace() }
                c == '/' && text.peek(i + 1) == '/' -> consumeLineComment(text, i)
                c == '/' && text.peek(i + 1) == '*' -> consumeBlockComment(text, i)
                c == '"' -> consumeString(text, i)
                c == '\'' -> consumeCharLiteral(text, i)
                c == '@' -> consumeAnnotation(text, i)
                c == '`' -> consumeBackticked(text, i)
                c.isDigit() -> consumeNumber(text, i)
                c == '.' && text.peek(i + 1)?.isDigit() == true -> consumeNumber(text, i)
                isIdentifierStart(c) -> consumeIdentifier(text, i)
                else -> i + 1
            }
            val type = classify(text, start, i)
            tokens.add(KotlinToken(type, start, i))
        }
        return tokens
    }

    // ------------------------------------------------------------------
    // Token consumers. Each returns the index just past the token.
    // ------------------------------------------------------------------

    private inline fun consumeWhile(text: String, from: Int, predicate: (Char) -> Boolean): Int {
        var i = from
        while (i < text.length && predicate(text[i])) i++
        return i
    }

    private fun consumeLineComment(text: String, from: Int): Int {
        var i = from + 2
        while (i < text.length && text[i] != '\n') i++
        return i
    }

    /** Kotlin block comments nest, so an inner block comment does not close the outer one. */
    private fun consumeBlockComment(text: String, from: Int): Int {
        var i = from + 2
        var depth = 1
        while (i < text.length && depth > 0) {
            when {
                text[i] == '/' && text.peek(i + 1) == '*' -> { depth++; i += 2 }
                text[i] == '*' && text.peek(i + 1) == '/' -> { depth--; i += 2 }
                else -> i++
            }
        }
        return i
    }

    /**
     * Consumes a `"…"` or `"""…"""` literal. Escape sequences and `${…}`
     * templates are *not* recursively lexed: nesting colors inside a string is
     * a cosmetic nicety, while keeping the lexer total matters more. An
     * unterminated literal runs to the end of the buffer.
     */
    private fun consumeString(text: String, from: Int): Int {
        if (text.startsWith("\"\"\"", from)) {
            val end = text.indexOf("\"\"\"", from + 3)
            return if (end < 0) text.length else end + 3
        }
        var i = from + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2 // skip the escaped character
                '"' -> return i + 1
                '\n' -> return i // a plain string cannot span a raw newline
                else -> i++
            }
        }
        return text.length
    }

    private fun consumeCharLiteral(text: String, from: Int): Int {
        var i = from + 1
        if (i < text.length && text[i] == '\\') i += 2 else i++
        while (i < text.length) {
            if (text[i] == '\'') return i + 1
            if (text[i] == '\n') return i
            i++
        }
        return text.length
    }

    /**
     * Consumes an annotation usage: `@Named`, `@file:JvmName`,
     * `@get:NotNull`, `@kotlin.jvm.JvmName`.
     */
    private fun consumeAnnotation(text: String, from: Int): Int {
        var i = from + 1
        i = consumeWhile(text, i) { isIdentifierPart(it) || it == '.' }
        // Optional use-site target, e.g. `@file:` / `@get:`.
        if (text.peek(i) == ':' && text.peek(i + 1)?.let { isIdentifierStart(it) } == true) {
            i = consumeWhile(text, i + 1) { isIdentifierPart(it) || it == '.' }
        }
        return i
    }

    private fun consumeBackticked(text: String, from: Int): Int {
        val end = text.indexOf('`', from + 1)
        return if (end < 0) text.length else end + 1
    }

    /**
     * Consumes a numeric literal. Deliberately conservative: it stops before a
     * `.` that is not followed by a digit, so `1.toString()` stays a number
     * followed by punctuation and an identifier.
     */
    private fun consumeNumber(text: String, from: Int): Int {
        var i = from
        if (text[i] == '0' && (text.peek(i + 1) == 'x' || text.peek(i + 1) == 'X')) {
            i = consumeWhile(text, i + 2) { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '_' }
        } else if (text[i] == '0' && (text.peek(i + 1) == 'b' || text.peek(i + 1) == 'B')) {
            i = consumeWhile(text, i + 2) { it == '0' || it == '1' || it == '_' }
        } else {
            i = consumeWhile(text, i) { it.isDigit() || it == '_' }
            if (text.peek(i) == '.' && text.peek(i + 1)?.isDigit() == true) {
                i = consumeWhile(text, i + 1) { it.isDigit() || it == '_' }
            }
            if (text.peek(i) == 'e' || text.peek(i) == 'E') {
                var j = i + 1
                if (text.peek(j) == '+' || text.peek(j) == '-') j++
                if (text.peek(j)?.isDigit() == true) i = consumeWhile(text, j) { it.isDigit() || it == '_' }
            }
        }
        // Optional type suffix: 1L, 1.5f, 10u, 10UL.
        while (i < text.length && text[i] in "fFdDlLuU") i++
        return i
    }

    private fun consumeIdentifier(text: String, from: Int): Int =
        consumeWhile(text, from) { isIdentifierPart(it) }

    // ------------------------------------------------------------------
    // Classification
    // ------------------------------------------------------------------

    private fun classify(text: String, start: Int, end: Int): TokenType {
        val c = text[start]
        return when {
            c.isWhitespace() -> TokenType.WHITESPACE
            c == '/' && text.peek(start + 1) == '/' -> TokenType.LINE_COMMENT
            c == '/' && text.peek(start + 1) == '*' -> TokenType.BLOCK_COMMENT
            c == '"' -> TokenType.STRING
            c == '\'' -> TokenType.CHAR
            c == '@' -> TokenType.ANNOTATION
            c == '`' -> TokenType.BACKTICKED
            c.isDigit() || (c == '.' && text.peek(start + 1)?.isDigit() == true) -> TokenType.NUMBER
            isIdentifierStart(c) -> {
                val word = text.substring(start, end)
                when {
                    word == "true" || word == "false" || word == "null" -> TokenType.LITERAL
                    word in HARD_KEYWORDS -> TokenType.KEYWORD
                    word in SOFT_KEYWORDS -> TokenType.SOFT_KEYWORD
                    else -> TokenType.IDENTIFIER
                }
            }
            else -> TokenType.PUNCTUATION
        }
    }

    private fun String.peek(index: Int): Char? = if (index in indices) this[index] else null

    private fun isIdentifierStart(c: Char): Boolean = c == '_' || c == '$' || c.isLetter()

    private fun isIdentifierPart(c: Char): Boolean = c == '_' || c == '$' || c.isLetterOrDigit()

    companion object {
        /**
         * Kotlin *hard* keywords: always reserved and impossible to use as
         * identifiers (without backticks).
         */
        val HARD_KEYWORDS: Set<String> = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
            "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
            "true", "try", "typealias", "typeof", "val", "var", "when", "while",
        )

        /**
         * Kotlin *soft* keywords and modifier keywords. They have no special
         * meaning in a general expression context but are visually useful to
         * distinguish from ordinary identifiers.
         */
        val SOFT_KEYWORDS: Set<String> = setOf(
            "actual", "abstract", "annotation", "by", "catch", "companion", "const", "constructor",
            "crossinline", "data", "delegate", "dynamic", "enum", "expect", "external", "field",
            "file", "final", "finally", "get", "import", "infix", "init", "inline", "inner",
            "internal", "it", "lateinit", "noinline", "open", "operator", "out", "override",
            "param", "private", "protected", "property", "public", "receiver", "reified", "sealed",
            "set", "setparam", "suspend", "tailrec", "value", "vararg", "where",
        )
    }
}
