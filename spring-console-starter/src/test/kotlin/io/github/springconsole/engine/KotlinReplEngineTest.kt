package io.github.springconsole.engine

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertInstanceOf
import kotlin.script.experimental.api.KotlinType
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A stand-in for a Spring bean injected into the REPL scope. */
class GreetingService {
    fun greet(name: String): String = "Hello, $name!"
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KotlinReplEngineTest {

    private val engine = KotlinReplEngine(
        bindings = listOf(
            ReplBinding("greetingService", KotlinType(GreetingService::class), GreetingService()),
        ),
        baseClassLoader = javaClass.classLoader,
    )

    @Test
    fun `evaluates a simple expression`() {
        val outcome = engine.eval("1 + 1")
        assertEquals("2", assertInstanceOf<SnippetOutcome.Success>(outcome).rendered)
    }

    @Test
    fun `keeps state across snippets`() {
        assertInstanceOf<SnippetOutcome.Success>(engine.eval("val stateTest = 21"))
        val outcome = engine.eval("stateTest * 2")
        assertEquals("42", assertInstanceOf<SnippetOutcome.Success>(outcome).rendered)
    }

    @Test
    fun `binds provided properties with their declared type`() {
        val outcome = engine.eval("greetingService.greet(\"Agent\")")
        assertEquals("Hello, Agent!", assertInstanceOf<SnippetOutcome.Success>(outcome).rendered)
    }

    @Test
    fun `returns null rendering for unit snippets`() {
        val outcome = engine.eval("val unused = 1")
        assertNull(assertInstanceOf<SnippetOutcome.Success>(outcome).rendered)
    }

    @Test
    fun `reports structured compilation errors with line and column`() {
        val outcome = engine.eval("val broken: Int = \"not an int\"")
        val errors = assertInstanceOf<SnippetOutcome.CompileError>(outcome).errors
        assertTrue(errors.isNotEmpty(), "expected at least one compilation error")
        val first = errors.first()
        assertEquals(1, first.line)
        assertTrue(first.column > 0, "expected a real column, got ${first.column}")
        assertTrue(first.message.isNotBlank())
    }

    @Test
    fun `reports unresolved references as compilation errors`() {
        val outcome = engine.eval("greetingService.noSuchMethod()")
        val errors = assertInstanceOf<SnippetOutcome.CompileError>(outcome).errors
        assertTrue(errors.first().message.contains("noSuchMethod"), "got: ${errors.first().message}")
    }

    @Test
    fun `surfaces runtime exceptions with the original throwable`() {
        val outcome = engine.eval("error(\"boom\")")
        val error = assertInstanceOf<SnippetOutcome.RuntimeError>(outcome)
        assertEquals("boom", error.exception.message)
        assertTrue(error.exception is IllegalStateException, "got ${error.exception.javaClass}")
    }

    @Test
    fun `snippet failure does not poison subsequent evaluations`() {
        engine.eval("error(\"first failure\")")
        val outcome = engine.eval("\"recovered\"")
        assertEquals("recovered", assertInstanceOf<SnippetOutcome.Success>(outcome).rendered)
    }

    @Test
    fun `evaluates Java-style variable declarations and preserves variable for subsequent snippets`() {
        val outcome = engine.eval("private String x;")
        assertInstanceOf<SnippetOutcome.Success>(outcome)

        val assign = engine.eval("x = \"hello\"; x")
        assertEquals("hello", assertInstanceOf<SnippetOutcome.Success>(assign).rendered)
    }
}
