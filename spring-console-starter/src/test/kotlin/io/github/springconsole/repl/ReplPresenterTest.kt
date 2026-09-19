package io.github.springconsole.repl

import io.github.springconsole.api.CompilationError
import io.github.springconsole.api.EvalResult
import io.github.springconsole.api.EvalStatus
import io.github.springconsole.api.ExceptionDetails
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [ReplPresenter] on an in-memory terminal. These assert the actual
 * user-visible text for every evaluation status, including the caret view for
 * compiler diagnostics and the sandbox footnote.
 */
class ReplPresenterTest {

    private val terminal = CapturingTerminal()
    private val presenter = ReplPresenter(terminal.output())

    @Test
    fun `successful collection values are rendered as a table`() {
        presenter.present(
            EvalResult(
                status = EvalStatus.SUCCESS,
                result = "[TodoRow(id=1, title=milk, completed=false)]",
                rawValue = listOf(TodoRow(1, "milk", false)),
            ),
        )

        val text = terminal.text()
        assertTrue(text.contains("List<TodoRow> (1 item)"), text)
        assertTrue(text.contains("id: long"), text)
        assertTrue(text.contains("milk"), text)
    }

    @Test
    fun `unit results print no value`() {
        presenter.present(EvalResult(status = EvalStatus.SUCCESS, result = null, rawValue = Unit))

        assertEquals("", terminal.text())
    }

    @Test
    fun `null results print the null literal`() {
        presenter.present(EvalResult(status = EvalStatus.SUCCESS, result = "null", rawValue = null))

        assertEquals("null", terminal.text().trim())
    }

    @Test
    fun `results without a raw value fall back to the rendered string`() {
        presenter.present(EvalResult(status = EvalStatus.SUCCESS, result = "42"))

        assertEquals("42", terminal.text().trim())
    }

    @Test
    fun `printed output precedes the rendered value`() {
        presenter.present(
            EvalResult(
                status = EvalStatus.SUCCESS,
                result = "2",
                rawValue = 2,
                printedOutput = "side channel\n",
            ),
        )

        val text = terminal.text()
        assertEquals(0, text.indexOf("side channel"))
        assertTrue(text.indexOf("side channel") < text.indexOf("2"), text)
    }

    @Test
    fun `printed output without a trailing newline does not glue to the value`() {
        presenter.present(EvalResult(status = EvalStatus.SUCCESS, result = "1", rawValue = 1, printedOutput = "no newline"))

        val lines = terminal.lines()
        assertEquals("no newline", lines[0])
        assertEquals("1", lines[1])
    }

    @Test
    fun `compilation errors render the source line and a caret`() {
        val input = "val x = todoService.noSuchMethod()"
        presenter.present(
            EvalResult(
                status = EvalStatus.COMPILATION_ERROR,
                compilationErrors = listOf(CompilationError(line = 1, column = 9, message = "Unresolved reference: x")),
            ),
            input,
        )

        val lines = terminal.lines()
        assertEquals("compile error [1:9] Unresolved reference: x", lines[0])
        assertEquals("  $input", lines[1])
        assertEquals("  " + " ".repeat(8) + "^", lines[2])
    }

    @Test
    fun `compilation errors on later lines point at the right source line`() {
        val input = "val a = 1\nval b = nope"
        presenter.present(
            EvalResult(
                status = EvalStatus.COMPILATION_ERROR,
                compilationErrors = listOf(CompilationError(line = 2, column = 9, message = "Unresolved reference: nope")),
            ),
            input,
        )

        val lines = terminal.lines()
        assertTrue(lines.contains("  val b = nope"), lines.toString())
        assertEquals("  " + " ".repeat(8) + "^", lines.last())
    }

    @Test
    fun `compilation errors without diagnostics still report failure`() {
        presenter.present(EvalResult(status = EvalStatus.COMPILATION_ERROR, compilationErrors = emptyList()), "x")

        assertTrue(terminal.text().contains("Compilation failed"), terminal.text())
    }

    @Test
    fun `runtime exceptions render type message and dimmed frames`() {
        presenter.present(
            EvalResult(
                status = EvalStatus.RUNTIME_EXCEPTION,
                exception = ExceptionDetails(
                    type = "java.lang.NullPointerException",
                    message = "boom",
                    // The engine's pruner emits frames already prefixed with "at ".
                    stackTrace = listOf("at com.example.Foo.bar(Foo.java:1)"),
                ),
            ),
        )

        val text = terminal.text()
        assertTrue(text.contains("java.lang.NullPointerException: boom"), text)
        assertTrue(text.contains("at com.example.Foo.bar(Foo.java:1)"), text)
    }

    @Test
    fun `runtime exceptions without details still report the status`() {
        presenter.present(EvalResult(status = EvalStatus.RUNTIME_EXCEPTION, result = "engine blew up"))

        assertTrue(terminal.text().contains("engine blew up"), terminal.text())
    }

    @Test
    fun `timeouts are reported as errors`() {
        presenter.present(EvalResult(status = EvalStatus.TIMEOUT, result = "Evaluation exceeded 5000ms"))
        assertTrue(terminal.text().contains("Evaluation exceeded 5000ms"), terminal.text())

        terminal.output().flush()
        presenter.present(EvalResult(status = EvalStatus.TIMEOUT))
        assertTrue(terminal.text().contains("Evaluation timed out"), terminal.text())
    }


    fun `persisted evaluations have no sandbox footnote`() {
        presenter.present(EvalResult(status = EvalStatus.SUCCESS, result = "1", rawValue = 1))

        assertTrue(!terminal.text().contains("rolled back"), terminal.text())
    }
}
