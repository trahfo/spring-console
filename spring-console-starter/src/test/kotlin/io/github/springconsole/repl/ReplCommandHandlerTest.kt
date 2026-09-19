package io.github.springconsole.repl

import io.github.springconsole.ConsoleService
import io.github.springconsole.SpringConsoleProperties
import io.github.springconsole.api.ReloadResult
import io.github.springconsole.api.ReloadStatus
import io.github.springconsole.fixture.ConsoleTestApp
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for the `:commands`, driven against the real test
 * application context (the same fixture the console tests use) and an
 * in-memory terminal. Every command the help text advertises is exercised.
 */
@SpringBootTest(
    classes = [ConsoleTestApp::class],
    properties = [
        "spring-console.enabled=false", // this test drives its own ConsoleService instance
        "spring.datasource.url=jdbc:h2:mem:repl-commands;DB_CLOSE_DELAY=-1",
        "logging.level.org.hibernate=warn",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReplCommandHandlerTest {

    @Autowired
    lateinit var context: ConfigurableApplicationContext

    private val console: ConsoleService by lazy { ConsoleService(context, SpringConsoleProperties()) }

    @AfterAll
    fun tearDown() {
        console.close()
    }

    /** Fresh terminal + handler per test, so command state never leaks between tests. */
    private inner class Harness(reload: ((Boolean) -> ReloadResult)? = null, consoleAvailable: Boolean = true) {
        val terminal = CapturingTerminal()
        val handler = ReplCommandHandler(
            output = terminal.output(),
            consoleSupplier = { if (consoleAvailable) console else null },
            reloadHandler = reload,
        )

        fun text(): String = terminal.text()
    }

    @Test
    fun `help lists every advertised command`() {
        val harness = Harness()
        harness.handler.printHelp()

        val text = harness.text()
        REPL_COMMANDS.forEach { command ->
            assertTrue(text.contains(command.name), "help is missing ${command.name}:\n$text")
        }
    }

    @Test
    fun `banner announces highlighting and completion`() {
        val harness = Harness()
        harness.handler.printBanner()

        val text = harness.text()
        assertTrue(text.contains("Tab"), text)
        assertTrue(text.contains("syntax-highlighted"), text)
    }

    @Test
    fun `quit detaches but keeps the application running`() {
        val harness = Harness()

        assertFalse(harness.handler.handle(":quit"), ":quit must stop the loop")
        assertTrue(harness.text().contains("detached"), harness.text())
    }

    @Test
    fun `unknown commands are reported without stopping the loop`() {
        val harness = Harness()

        assertTrue(harness.handler.handle(":bogus"))
        assertTrue(harness.text().contains("Unknown command :bogus"), harness.text())
    }

    @Test
    fun `inspect without an argument prints usage`() {
        val harness = Harness()
        harness.handler.handle(":inspect")

        assertTrue(harness.text().contains("Usage: :inspect"), harness.text())
    }

    @Test
    fun `inspect prints the unwrapped type and method signatures`() {
        val harness = Harness()
        harness.handler.handle(":inspect noteService")

        val text = harness.text()
        assertTrue(text.contains("io.github.springconsole.fixture.NoteService"), text)
        assertTrue(text.contains("add("), text)
        assertTrue(text.contains("count()"), text)
    }

    @Test
    fun `inspect reports unknown beans`() {
        val harness = Harness()
        harness.handler.handle(":inspect doesNotExist")

        assertTrue(harness.text().contains("No bean named 'doesNotExist'"), harness.text())
    }

    @Test
    fun `beans lists bound beans and honors a package filter`() {
        val harness = Harness()
        harness.handler.handle(":beans io.github.springconsole.fixture")

        val text = harness.text()
        assertTrue(text.contains("noteService"), text)
        assertTrue(text.contains("io.github.springconsole.fixture.NoteService"), text)
        assertTrue(text.contains("beans bound"), text)
    }

    @Test
    fun `schema dumps entities repositories and services`() {
        val harness = Harness()
        harness.handler.handle(":schema")

        val text = harness.text()
        assertTrue(text.contains("entity"), text)
        assertTrue(text.contains("Note"), text)
        assertTrue(text.contains("noteRepository"), text)
        assertTrue(text.contains("noteService"), text)
    }

    @Test
    fun `reload reports unavailability when no handler exists`() {
        val harness = Harness(reload = null)
        harness.handler.handle(":reload")

        assertTrue(harness.text().contains("Reload is not available"), harness.text())
    }

    @Test
    fun `reload reports success`() {
        val harness = Harness(reload = { ReloadResult(ReloadStatus.SUCCESS, "recompiled and restarted") })
        harness.handler.handle(":reload")

        val text = harness.text()
        assertTrue(text.contains("Recompiling and restarting"), text)
        assertTrue(text.contains("recompiled and restarted"), text)
    }

    @Test
    fun `reload reports structured compilation errors`() {
        val harness = Harness(
            reload = {
                ReloadResult(
                    status = ReloadStatus.COMPILATION_ERROR,
                    message = "compilation failed",
                    compilationErrors = listOf(
                        io.github.springconsole.api.CompilationError(12, 4, "Unresolved reference: nope"),
                    ),
                )
            },
        )
        harness.handler.handle(":reload")

        val text = harness.text()
        assertTrue(text.contains("compilation failed"), text)
        assertTrue(text.contains("12:4 Unresolved reference: nope"), text)
    }

    @Test
    fun `reload passes the no-compile flag through`() {
        var received: Boolean? = null
        val harness = Harness(reload = { recompile -> received = recompile; ReloadResult(ReloadStatus.SUCCESS, "ok") })

        harness.handler.handle(":reload --no-compile")
        assertFalse(received!!, "--no-compile must disable recompilation")

        harness.handler.handle(":reload")
        assertTrue(received!!, "plain :reload must recompile")
    }

    @Test
    fun `commands fail gracefully when the context is unavailable`() {
        val harness = Harness(consoleAvailable = false)

        assertTrue(harness.handler.handle(":beans"))
        assertTrue(harness.handler.handle(":schema"))
        assertTrue(harness.text().contains("Application context is not available"), harness.text())
    }
}
