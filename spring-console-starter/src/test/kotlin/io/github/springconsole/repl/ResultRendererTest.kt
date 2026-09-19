package io.github.springconsole.repl

import java.time.LocalDate
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Kotlin data class fixture: getter-based object rendering. */
data class TodoRow(val id: Long, val title: String, val completed: Boolean)

/** Enum fixture. */
enum class Priority { LOW, HIGH }

/** Object with methods but no readable properties. */
class NoProperties {
    fun doIt(): String = "x"
}

/** One property that throws and one that reads fine. */
class ThrowingProperty {
    val boom: String get() = throw IllegalStateException("nope")
    val ok: String = "fine"
}

/** Name contains "HibernateProxy", so the renderer must never call toString(). */
class FakeHibernateProxy {
    override fun toString(): String = throw IllegalStateException("lazy initialization")
}

/** Ten properties, used for the column cap. */
data class Wide(
    val p1: Int = 1,
    val p2: Int = 2,
    val p3: Int = 3,
    val p4: Int = 4,
    val p5: Int = 5,
    val p6: Int = 6,
    val p7: Int = 7,
    val p8: Int = 8,
    val p9: Int = 9,
    val p10: Int = 10,
)

/**
 * Unit tests for [ResultRenderer] — the component behind the "present the
 * variable's content in a nice, color-coded view" acceptance criterion.
 */
class ResultRendererTest {

    private val renderer = ResultRenderer()

    private fun text(value: Any?): String = renderer.render(value).joinToString("\n") { it.toString() }

    private fun text(renderer: ResultRenderer, value: Any?): String =
        renderer.render(value).joinToString("\n") { it.toString() }

    // ------------------------------------------------------------------
    // Scalars
    // ------------------------------------------------------------------

    @Test
    fun `null renders as a dim null literal`() {
        val lines = renderer.render(null)
        assertEquals(1, lines.size)
        assertEquals("null", lines[0].toString())
        assertEquals(ReplTheme.valueNull, lines[0].styleAt(0))
    }

    @Test
    fun `Unit renders nothing`() {
        assertEquals(emptyList(), renderer.render(Unit))
    }

    @Test
    fun `scalars are color coded`() {
        assertEquals(ReplTheme.valueNumber, renderer.render(42)[0].styleAt(0))
        assertEquals(ReplTheme.valueBoolean, renderer.render(true)[0].styleAt(0))
        assertEquals(ReplTheme.valueString, renderer.render("hi")[0].styleAt(0))
        assertEquals(ReplTheme.valueString, renderer.render('x')[0].styleAt(0))
        assertEquals(ReplTheme.valueEnum, renderer.render(Priority.HIGH)[0].styleAt(0))
        assertEquals(ReplTheme.valueTemporal, renderer.render(LocalDate.of(2025, 1, 2))[0].styleAt(0))
    }

    @Test
    fun `strings are quoted and truncation is reported`() {
        assertTrue(text("hi").contains("\"hi\""), text("hi"))

        val long = "x".repeat(500)
        val rendered = text(long)
        assertTrue(rendered.contains("(500 chars)"), rendered)
        assertTrue(rendered.contains("…"), rendered)
    }

    @Test
    fun `newlines in strings are escaped so the layout survives`() {
        val rendered = text("line1\nline2")
        assertTrue(rendered.contains("\\n"), rendered)
        assertFalse(rendered.contains("line1\nline2"), rendered)
    }

    // ------------------------------------------------------------------
    // Collections
    // ------------------------------------------------------------------

    @Test
    fun `collections of scalars render as a one column table`() {
        val rendered = text(listOf("a", "b"))

        assertTrue(rendered.contains("List<String> (2 items)"), rendered)
        assertTrue(rendered.contains("value"), rendered)
        assertTrue(rendered.contains("a"), rendered)
        assertTrue(rendered.contains("b"), rendered)
        assertTrue(rendered.contains("─"), "expected a table rule in:\n$rendered")
    }

    @Test
    fun `collections of objects render as a property table with typed columns`() {
        val rendered = text(listOf(TodoRow(1, "milk", false), TodoRow(2, "eggs", true)))

        assertTrue(rendered.contains("List<TodoRow> (2 items)"), rendered)
        assertTrue(rendered.contains("id: long"), rendered)
        assertTrue(rendered.contains("title: String"), rendered)
        assertTrue(rendered.contains("completed: boolean"), rendered)
        assertTrue(rendered.contains("milk") && rendered.contains("eggs"), rendered)
    }

    @Test
    fun `table cells are color coded by value type`() {
        val lines = renderer.render(listOf(TodoRow(1, "milk", false)))
        val milkLine = lines.first { it.toString().contains("milk") }
        val index = milkLine.toString().indexOf("milk")

        assertEquals(ReplTheme.valueString, milkLine.styleAt(index))
    }

    @Test
    fun `large collections are truncated with a notice`() {
        val rendered = text(ResultRenderer(maxRows = 2), (1..5).toList())

        assertTrue(rendered.contains("(5 items)"), rendered)
        assertTrue(rendered.contains("… and 3 more items"), rendered)
    }

    @Test
    fun `column count is capped`() {
        val lines = ResultRenderer(maxColumns = 3).render(listOf(Wide()))
        val header = lines[1].toString()

        assertEquals(2, header.count { it == '│' }, "expected 3 columns in: $header")
    }

    @Test
    fun `empty collections render an explicit empty marker`() {
        assertTrue(text(emptyList<String>()).contains("(empty)"), text(emptyList<String>()))
    }

    @Test
    fun `arrays sequences sets and streams are tabulated`() {
        assertTrue(text(intArrayOf(1, 2, 3)).contains("int[] (3 items)"), text(intArrayOf(1, 2, 3)))
        assertTrue(text(arrayOf("a")).contains("String[] (1 item)"), text(arrayOf("a")))
        assertTrue(text(sequenceOf(1, 2)).contains("Sequence (showing 2 items)"), text(sequenceOf(1, 2)))
        assertTrue(text(setOf(1, 2)).contains("Set<Integer> (2 items)"), text(setOf(1, 2)))
        assertTrue(
            text(java.util.stream.Stream.of("a", "b")).contains("Stream (showing 2 items)"),
            text(java.util.stream.Stream.of("a", "b")),
        )
    }

    @Test
    fun `lazy sources report that the shown count may be partial`() {
        val rendered = text(ResultRenderer(maxRows = 1), generateSequence(1) { it + 1 })

        assertTrue(rendered.contains("showing 1+ item"), rendered)
        assertTrue(rendered.contains("more items not shown"), rendered)
    }

    // ------------------------------------------------------------------
    // Objects
    // ------------------------------------------------------------------

    @Test
    fun `java records render as a key value block`() {
        val rendered = text(TodoRecord(1, "milk", false))

        assertTrue(rendered.contains("TodoRecord  (3 properties)"), rendered)
        assertTrue(rendered.contains("id"), rendered)
        assertTrue(rendered.contains("milk"), rendered)
        assertEquals(ReplTheme.key, renderer.render(TodoRecord(1, "milk", false))[1].styleAt(2))
    }

    @Test
    fun `objects without properties list their methods`() {
        val rendered = text(NoProperties())

        assertTrue(rendered.contains("NoProperties"), rendered)
        assertTrue(rendered.contains("doIt()"), rendered)
        assertTrue(rendered.contains("String"), rendered)
    }

    @Test
    fun `a property that throws is reported without aborting the render`() {
        val rendered = text(ThrowingProperty())

        assertTrue(rendered.contains("<unavailable:"), rendered)
        assertTrue(rendered.contains("nope"), rendered)
        assertTrue(rendered.contains("fine"), rendered)
    }

    @Test
    fun `hibernate proxies are never summarized via toString`() {
        val rendered = text(listOf(FakeHibernateProxy()))

        assertTrue(rendered.contains("lazy proxy"), rendered)
    }

    @Test
    fun `maps render as a two column table`() {
        val rendered = text(mapOf("a" to 1, "b" to 2))

        assertTrue(rendered.contains("Map (2 entries)"), rendered)
        assertTrue(rendered.contains("key"), rendered)
        assertTrue(rendered.contains("value"), rendered)
        assertTrue(rendered.contains("a") && rendered.contains("b"), rendered)
    }

    @Test
    fun `optionals unwrap to their value`() {
        val present = text(Optional.of("x"))
        assertTrue(present.contains("Optional"), present)
        assertTrue(present.contains("\"x\""), present)

        assertTrue(text(Optional.empty<String>()).contains("Optional.empty"), text(Optional.empty<String>()))
    }

    @Test
    fun `pairs and triples render as key value blocks`() {
        assertTrue(text(1 to "one").contains("Pair"), text(1 to "one"))
        assertTrue(text(Triple(1, 2, 3)).contains("Triple"), text(Triple(1, 2, 3)))
    }

    @Test
    fun `throwables render their type and message`() {
        val rendered = text(IllegalStateException("boom"))
        assertTrue(rendered.contains("java.lang.IllegalStateException: boom"), rendered)
        assertTrue(rendered.contains("at "), rendered)
    }

    // ------------------------------------------------------------------
    // Output and robustness
    // ------------------------------------------------------------------

    @Test
    fun `ANSI output contains escape sequences`() {
        val ansi = renderer.renderAnsi(listOf(TodoRow(1, "milk", false)))

        assertTrue(ansi.contains("\u001b["), "expected ANSI codes in: $ansi")
        assertTrue(ansi.contains("milk"), ansi)
    }

    @Test
    fun `rendering never throws for hostile values`() {
        val hostile = listOf<Any?>(
            object {
                @Suppress("unused")
                val broken: Any get() = throw LinkageError("missing type")
            },
        )
        renderer.render(hostile)
    }

    @Test
    fun `numbers report their boxed type`() {
        assertTrue(text(1L).contains("(Long)"), text(1L))
        assertTrue(text(2.0).contains("(Double)"), text(2.0))
    }
}
