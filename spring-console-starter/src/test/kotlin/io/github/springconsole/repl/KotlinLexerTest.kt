package io.github.springconsole.repl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the highlighter's lexer. The two properties that matter for a
 * *highlighter* (as opposed to a compiler front-end) are covered explicitly:
 * tokens must tile the whole input, and tokenization must never throw on
 * incomplete input.
 */
class KotlinLexerTest {

    private val lexer = KotlinLexer()

    private fun tokensOf(text: String) = lexer.tokenize(text)

    private fun textsOf(text: String, type: TokenType): List<String> =
        tokensOf(text).filter { it.type == type }.map { text.substring(it.start, it.end) }

    @Test
    fun `tokens tile the whole input without gaps or overlaps`() {
        val text = """
            val x = todoService.findById(1L) // note
            val s = "a\"b" /* block */ 42
        """.trimIndent()

        val tokens = tokensOf(text)

        assertEquals(text, tokens.joinToString("") { text.substring(it.start, it.end) })
        var expectedStart = 0
        tokens.forEach { token ->
            assertEquals(expectedStart, token.start, "gap or overlap before token $token")
            expectedStart = token.end
        }
        assertEquals(text.length, expectedStart)
    }

    @Test
    fun `empty input produces no tokens`() {
        assertEquals(emptyList(), tokensOf(""))
    }

    @Test
    fun `classifies keywords soft keywords identifiers and literals`() {
        assertEquals(listOf("val"), textsOf("val answer = true", TokenType.KEYWORD))
        assertEquals(listOf("data"), textsOf("data class Foo", TokenType.SOFT_KEYWORD))
        assertEquals(listOf("class"), textsOf("data class Foo", TokenType.KEYWORD))
        assertEquals(listOf("true", "false", "null"), textsOf("true false null", TokenType.LITERAL))
        assertEquals(listOf("answer", "Foo"), textsOf("answer Foo", TokenType.IDENTIFIER))
    }

    @Test
    fun `classifies strings chars and escape sequences`() {
        val text = """val a = "hello \"world\""; val b = 'x'; val c = '\''"""
        assertEquals(1, textsOf(text, TokenType.STRING).size)
        assertTrue(textsOf(text, TokenType.STRING).single().startsWith("\"hello"))
        assertEquals(2, textsOf(text, TokenType.CHAR).size, "char literals: ${textsOf(text, TokenType.CHAR)}")
    }

    @Test
    fun `triple quoted raw strings span newlines`() {
        val text = "val raw = \"\"\"line1\nline2\"\"\" + 1"
        val strings = textsOf(text, TokenType.STRING)
        assertEquals(1, strings.size)
        assertTrue(strings.single().contains("line2"), "got: ${strings.single()}")
        assertEquals(listOf("1"), textsOf(text, TokenType.NUMBER))
    }

    @Test
    fun `nestable block comments are a single token`() {
        val text = "/* outer /* inner */ still */ 1"
        val comments = textsOf(text, TokenType.BLOCK_COMMENT)
        assertEquals(listOf("/* outer /* inner */ still */"), comments)
        assertEquals(listOf("1"), textsOf(text, TokenType.NUMBER))
    }

    @Test
    fun `line comments stop at the newline`() {
        val text = "1 // comment\n2"
        assertEquals(listOf("// comment"), textsOf(text, TokenType.LINE_COMMENT))
        assertEquals(listOf("1", "2"), textsOf(text, TokenType.NUMBER))
    }

    @Test
    fun `numbers cover radix separators fractions exponents and suffixes`() {
        val text = "0xFF 1_000L 2.5e-3f 0b1010 42u"
        assertEquals(listOf("0xFF", "1_000L", "2.5e-3f", "0b1010", "42u"), textsOf(text, TokenType.NUMBER))
    }

    @Test
    fun `a trailing dot does not swallow a member call`() {
        val text = "1.toString()"
        assertEquals(listOf("1"), textsOf(text, TokenType.NUMBER))
        assertEquals(listOf("toString"), textsOf(text, TokenType.IDENTIFIER))
    }

    @Test
    fun `annotations and backticked identifiers are recognized`() {
        assertEquals(listOf("@Service", "@file:JvmName"), textsOf("@Service @file:JvmName class", TokenType.ANNOTATION))
        assertEquals(listOf("`my bean`"), textsOf("`my bean`.toString()", TokenType.BACKTICKED))
    }

    @Test
    fun `unterminated constructs extend to the end without throwing`() {
        val unterminated = listOf("\"oops", "/* oops", "`oops", "'", "\"\"\"oops", "\\", "\$", "${'$'}{")
        unterminated.forEach { text ->
            val tokens = tokensOf(text)
            assertEquals(text, tokens.joinToString("") { text.substring(it.start, it.end) }, "input: $text")
        }
    }

    @Test
    fun `unicode identifiers are treated as identifiers`() {
        assertEquals(listOf("größe"), textsOf("größe = 1", TokenType.IDENTIFIER))
    }
}
