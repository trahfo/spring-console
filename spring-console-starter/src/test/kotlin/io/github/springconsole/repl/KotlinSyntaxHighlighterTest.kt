package io.github.springconsole.repl

import org.jline.utils.AttributedStyle
import java.util.regex.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Tests for the JLine [org.jline.reader.Highlighter] implementation. These
 * cover both halves of the contract:
 *
 * 1. the buffer must come back unchanged in text and length, with the expected
 *    per-character styles (otherwise JLine's cursor arithmetic breaks), and
 * 2. the error-position API (`setErrorIndex` / `setErrorPattern`) must mark the
 *    offending range on top of the syntax colors.
 */
class KotlinSyntaxHighlighterTest {

    private val highlighter = KotlinSyntaxHighlighter()

    private fun styleAt(buffer: String, needle: String, occurrence: Int = 0): AttributedStyle {
        val highlighted = highlighter.highlight(null, buffer)
        var index = -1
        repeat(occurrence + 1) { index = buffer.indexOf(needle, index + 1) }
        check(index >= 0) { "needle '$needle' not found in '$buffer'" }
        return highlighted.styleAt(index)
    }

    @Test
    fun `returns a string of identical length and text`() {
        val buffer = """val greeting = "hello" // comment"""
        val highlighted = highlighter.highlight(null, buffer)

        assertEquals(buffer.length, highlighted.length)
        assertEquals(buffer, highlighted.toString())
    }

    @Test
    fun `colors keywords strings numbers identifiers and comments`() {
        val buffer = """val s = "hi" + 42 // tail"""

        assertEquals(ReplTheme.keyword, styleAt(buffer, "val"))
        assertEquals(ReplTheme.string, styleAt(buffer, "\"hi\""))
        assertEquals(ReplTheme.number, styleAt(buffer, "42"))
        assertEquals(ReplTheme.comment, styleAt(buffer, "//"))
        assertEquals(ReplTheme.identifier, styleAt(buffer, "s"))
        assertEquals(ReplTheme.softKeyword, styleAt("suspend fun x() = 1", "suspend"))
        assertEquals(ReplTheme.literal, styleAt("val b = true", "true"))
        assertEquals(ReplTheme.annotation, styleAt("@Service class X", "@Service"))
        assertEquals(ReplTheme.backticked, styleAt("`a b` + 1", "`a b`"))
    }

    @Test
    fun `produces ANSI escape sequences for supported terminals`() {
        val highlighted = highlighter.highlight(null, "val x = 1")
        val ansi = highlighted.toAnsi()

        assertEquals(true, ansi.contains("\u001b["), "expected escape sequences in: $ansi")
        assertEquals("val x = 1", org.jline.utils.AttributedString.stripAnsi(ansi))
    }

    @Test
    fun `error index marks exactly one character`() {
        val buffer = "val x = 1"
        val errorAt = buffer.indexOf('x')
        highlighter.setErrorIndex(errorAt)
        try {
            val highlighted = highlighter.highlight(null, buffer)
            assertEquals(ReplTheme.errorMarker, highlighted.styleAt(errorAt))
            assertNotEquals(ReplTheme.errorMarker, highlighted.styleAt(errorAt + 1))
        } finally {
            highlighter.setErrorIndex(-1)
        }
    }

    @Test
    fun `error pattern marks the first match only`() {
        val buffer = "oops + oops"
        highlighter.setErrorPattern(Pattern.compile("oops"))
        try {
            val highlighted = highlighter.highlight(null, buffer)
            assertEquals(ReplTheme.errorMarker, highlighted.styleAt(0))
            assertEquals(ReplTheme.identifier, highlighted.styleAt(buffer.lastIndexOf("oops")))
        } finally {
            highlighter.setErrorPattern(null)
        }
    }

    @Test
    fun `error styling overrides syntax colors`() {
        val buffer = "val x = 1"
        highlighter.setErrorPattern(Pattern.compile("val"))
        try {
            assertEquals(ReplTheme.errorMarker, highlighter.highlight(null, buffer).styleAt(0))
        } finally {
            highlighter.setErrorPattern(null)
        }
    }

    @Test
    fun `clearing the error index removes the marker`() {
        val buffer = "val x = 1"
        highlighter.setErrorIndex(0)
        assertEquals(ReplTheme.errorMarker, highlighter.highlight(null, buffer).styleAt(0))

        highlighter.setErrorIndex(-1)
        assertEquals(ReplTheme.keyword, highlighter.highlight(null, buffer).styleAt(0))
    }

    @Test
    fun `error index outside the buffer is ignored`() {
        highlighter.setErrorIndex(9_999)
        try {
            assertEquals("val x".length, highlighter.highlight(null, "val x").length)
        } finally {
            highlighter.setErrorIndex(-1)
        }
    }

    @Test
    fun `handles empty and pathological buffers`() {
        assertEquals(0, highlighter.highlight(null, "").length)
        listOf("\"unclosed", "/* unclosed", "`unclosed", "${'$'}{", "\\").forEach { buffer ->
            assertEquals(buffer, highlighter.highlight(null, buffer).toString(), "buffer: $buffer")
        }
    }

    @Test
    fun `refresh is a safe no-op`() {
        highlighter.refresh(null)
    }
}
