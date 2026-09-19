package io.github.springconsole.repl

import io.github.springconsole.ConsoleService
import io.github.springconsole.SpringConsoleProperties
import io.github.springconsole.fixture.ConsoleTestApp
import io.github.springconsole.fixture.Note
import io.github.springconsole.fixture.NoteRepository
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end test of the acceptance criterion:
 *
 * > when a method returns a variable, or the variable is just named and Enter
 * > is pressed, the content of that variable is presented in a nice view in the
 * > terminal, with color coding for readability.
 *
 * Unlike [ResultRendererTest], which unit-tests the renderer, this test runs
 * the **whole production path**: a real `ConsoleService` evaluates Kotlin
 * against a real Spring context, the engine returns the raw value, and
 * [ReplPresenter] renders it to an in-memory terminal.
 */
@SpringBootTest(
    classes = [ConsoleTestApp::class],
    properties = [
        "spring-console.enabled=false", // this test drives its own ConsoleService instance
        "spring.datasource.url=jdbc:h2:mem:repl-pipeline;DB_CLOSE_DELAY=-1",
        "logging.level.org.hibernate=warn",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleReplPipelineTest {

    @Autowired
    lateinit var context: ConfigurableApplicationContext

    @Autowired
    lateinit var noteRepository: NoteRepository

    private val console: ConsoleService by lazy { ConsoleService(context, SpringConsoleProperties()) }

    @BeforeEach
    fun resetData() {
        noteRepository.deleteAll()
        noteRepository.save(Note("alpha"))
        noteRepository.save(Note("beta"))
    }

    @AfterAll
    fun tearDown() {
        console.close()
    }

    /** Evaluates [code] and returns exactly what the terminal would show. */
    private fun present(code: String): String {
        val terminal = CapturingTerminal()
        val presenter = ReplPresenter(terminal.output())
        val result = console.eval(code)
        presenter.present(result, code)
        return terminal.text()
    }

    @Test
    fun `a repository result is shown as a color-coded table`() {
        val rendered = present("noteRepository.findAll()")

        assertTrue(rendered.contains("List<Note>"), rendered)
        assertTrue(rendered.contains("title: String"), rendered)
        assertTrue(rendered.contains("alpha"), rendered)
        assertTrue(rendered.contains("beta"), rendered)
        assertTrue(rendered.contains("─"), rendered)
    }

    @Test
    fun `naming a previously declared variable shows its content`() {
        // Declaring a val is a statement: no value is printed.
        assertEquals("", present("val titles = listOf(\"alpha\", \"beta\")").trim())

        // Naming the variable renders its content, exactly as the criterion requires.
        val rendered = present("titles")
        assertTrue(rendered.contains("List<String> (2 items)"), rendered)
        assertTrue(rendered.contains("alpha"), rendered)
        assertTrue(rendered.contains("beta"), rendered)
    }

    @Test
    fun `naming a bean lists its callable methods instead of a bare toString`() {
        val rendered = present("noteService")

        assertTrue(rendered.contains("NoteService"), rendered)
        assertTrue(rendered.contains("add("), rendered)
        assertTrue(rendered.contains("count()"), rendered)
        assertTrue(rendered.contains("Spring proxy"), rendered)
    }

    @Test
    fun `a scalar result is rendered on one line`() {
        assertTrue(present("noteService.count()").trim().startsWith("2"), present("noteService.count()"))
    }

    @Test
    fun `a null result is rendered as the null literal`() {
        assertEquals("null", present("noteRepository.findById(999999L).orElse(null)").trim())
    }

    @Test
    fun `printed output is shown before the rendered value`() {
        val rendered = present("println(\"from the snippet\"); listOf(1, 2)")

        assertTrue(rendered.contains("from the snippet"), rendered)
        assertTrue(rendered.contains("List<Integer> (2 items)"), rendered)
        assertTrue(rendered.indexOf("from the snippet") < rendered.indexOf("List<Integer>"), rendered)
    }

    @Test
    fun `compiler errors are shown with a caret`() {
        val code = "noteService.noSuchMethod()"
        val rendered = present(code)

        assertTrue(rendered.contains("compile error"), rendered)
        assertTrue(rendered.contains("  $code"), rendered)
        assertTrue(rendered.contains("^"), rendered)
    }

    @Test
    fun `runtime exceptions are shown with the exception type`() {
        val rendered = present("noteRepository.findById(999999L).get()")

        assertTrue(rendered.contains("java.util.NoSuchElementException"), rendered)
    }

    @Test
    fun `mutations are persisted directly with permanent consequences`() {
        val countBefore = noteRepository.count()
        val rendered = present("noteService.add(\"kept\")")

        assertTrue(!rendered.contains("rolled back"), rendered)
        assertEquals(countBefore + 1, noteRepository.count())
    }

    @Test
    fun `a multi-line snippet is evaluated as one unit`() {
        val rendered = present(
            """
            val numbers = listOf(1, 2, 3)
            numbers.map { it * 2 }
            """.trimIndent(),
        )

        assertTrue(rendered.contains("List<Integer> (3 items)"), rendered)
        assertTrue(rendered.contains("2") && rendered.contains("6"), rendered)
    }
}
