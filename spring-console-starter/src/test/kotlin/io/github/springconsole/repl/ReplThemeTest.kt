package io.github.springconsole.repl

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Verifies that the palette actually produces ANSI color codes and that
 * semantically different tokens get visually different styles. This is the
 * machine-checkable half of "color coding for readability": the renderer tests
 * assert which style is applied where, and these tests assert that the styles
 * themselves are distinct, colored, and non-empty.
 */
class ReplThemeTest {

    private fun ansi(style: AttributedStyle): String = AttributedString("x", style).toAnsi()

    @Test
    fun `interface chrome uses distinct colors`() {
        assertTrue(ansi(ReplTheme.success).contains("32"), "success should be green: ${ansi(ReplTheme.success)}")
        assertTrue(ansi(ReplTheme.error).contains("31"), "error should be red: ${ansi(ReplTheme.error)}")
        assertTrue(ansi(ReplTheme.warning).contains("33"), "warning should be yellow: ${ansi(ReplTheme.warning)}")
        assertTrue(ansi(ReplTheme.info).contains("36"), "info should be cyan: ${ansi(ReplTheme.info)}")
    }

    @Test
    fun `syntax styles are distinct`() {
        val styles = listOf(
            ReplTheme.keyword,
            ReplTheme.softKeyword,
            ReplTheme.string,
            ReplTheme.number,
            ReplTheme.literal,
            ReplTheme.comment,
            ReplTheme.annotation,
            ReplTheme.backticked,
            ReplTheme.identifier,
        )

        assertEquals(styles.size, styles.toSet().size, "two syntax styles are identical: $styles")
        assertNotEquals(ReplTheme.identifier, ReplTheme.keyword)
    }

    @Test
    fun `value styles are distinct and color coded`() {
        assertEquals(true, ansi(ReplTheme.valueString).contains("32"), ansi(ReplTheme.valueString))
        assertEquals(true, ansi(ReplTheme.valueNumber).contains("33"), ansi(ReplTheme.valueNumber))
        assertEquals(true, ansi(ReplTheme.valueEnum).contains("35"), ansi(ReplTheme.valueEnum))
        assertEquals(true, ansi(ReplTheme.valueTemporal).contains("36"), ansi(ReplTheme.valueTemporal))
        assertEquals(true, ansi(ReplTheme.errorMarker).contains("4"), "error marker must be underlined: ${ansi(ReplTheme.errorMarker)}")
    }

    @Test
    fun `every style renders as ANSI with an explicit color or attribute`() {
        val styles = listOf(
            ReplTheme.prompt, ReplTheme.success, ReplTheme.info, ReplTheme.error, ReplTheme.errorMarker,
            ReplTheme.warning, ReplTheme.dim, ReplTheme.faint, ReplTheme.border,
            ReplTheme.keyword, ReplTheme.softKeyword, ReplTheme.string, ReplTheme.number, ReplTheme.literal,
            ReplTheme.comment, ReplTheme.annotation, ReplTheme.backticked,
            ReplTheme.typeName, ReplTheme.key, ReplTheme.header,
            ReplTheme.valueString, ReplTheme.valueNumber, ReplTheme.valueBoolean, ReplTheme.valueEnum,
            ReplTheme.valueTemporal, ReplTheme.valueNull, ReplTheme.valueSummary, ReplTheme.valueNone,
        )

        styles.forEach { style ->
            assertTrue(ansi(style).contains("\u001b["), "style produced no ANSI: $style")
        }
    }
}
