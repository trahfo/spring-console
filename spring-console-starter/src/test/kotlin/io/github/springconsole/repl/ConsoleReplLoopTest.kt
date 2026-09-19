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
 * Drives the **real** [ConsoleRepl] read-eval-print loop with scripted
 * keystrokes on an in-memory terminal.
 *
 * Unlike the other REPL tests, which call the completer/highlighter/renderer
 * directly, this one exercises the full production wiring: JLine reads the
 * lines (so `\t` really triggers completion and `\n` really submits), the loop
 * evaluates against a live Spring context, and everything the user would see is
 * asserted from the captured terminal output. It is the closest thing to "a
 * human typed this" that a test can do.
 */
@SpringBootTest(
    classes = [ConsoleTestApp::class],
    properties = [
        "spring-console.enabled=false", // this test drives its own ConsoleService instance
        "spring.datasource.url=jdbc:h2:mem:repl-loop;DB_CLOSE_DELAY=-1",
        "logging.level.org.hibernate=warn",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleReplLoopTest {

    @Autowired
    lateinit var context: ConfigurableApplicationContext

    @Autowired
    lateinit var noteRepository: NoteRepository

    private val console: ConsoleService by lazy { ConsoleService(context, SpringConsoleProperties()) }

    @BeforeEach
    fun seedData() {
        noteRepository.deleteAll()
        noteRepository.save(Note("alpha"))
        noteRepository.save(Note("beta"))
    }

    @AfterAll
    fun tearDown() {
        console.close()
    }

    /** Runs a scripted session and returns everything written to the terminal. */
    private fun runSession(vararg lines: String): String {
        val terminal = CapturingTerminal(lines.joinToString(separator = "\n", postfix = "\n"))
        val repl = ConsoleRepl(
            consoleSupplier = { console },
            reloadHandler = null,
            terminalFactory = { terminal.terminal },
            requireInteractiveTerminal = false,
            historyFile = null,
        )

        assertTrue(repl.startIfInteractive(), "the REPL should start on a scripted terminal")
        assertTrue(repl.awaitTermination(30_000), "the REPL loop did not finish\n${terminal.text()}")
        repl.close()
        return terminal.text()
    }

    @Test
    fun `a scripted session evaluates renders and quits`() {
        val output = runSession(
            "val doubled = 21 * 2",
            "doubled",
            "noteRepository.findAll()",
            ":help",
            ":quit",
        )

        assertTrue(output.contains("Spring Console"), output)
        assertTrue(output.contains("42"), "the named variable should render its value:\n$output")
        assertTrue(output.contains("List<Note> (2 items)"), output)
        assertTrue(output.contains("alpha") && output.contains("beta"), output)
        assertTrue(output.contains(":beans"), "help should be shown:\n$output")
        assertTrue(output.contains("detached"), output)
    }

    @Test
    fun `the interactive reader is wired with completion and highlighting`() {
        val terminal = CapturingTerminal()
        val repl = ConsoleRepl(
            consoleSupplier = { console },
            terminalFactory = { terminal.terminal },
            requireInteractiveTerminal = false,
            historyFile = null,
        )

        val variables = linkedSetOf("overdue")
        val reader = repl.createReader(terminal.terminal, variables)
        val completer = repl.createCompleter(variables)
        try {
            assertTrue(reader.highlighter is KotlinSyntaxHighlighter, "highlighter: ${reader.highlighter}")

            // The completer handed to the reader knows the live context and the
            // session variables.
            val parsed = reader.parser.parse("noteService.", 12, org.jline.reader.Parser.ParseContext.COMPLETE)
            val members = mutableListOf<org.jline.reader.Candidate>()
            completer.complete(reader, parsed, members)
            assertTrue(
                members.any { it.value() == "noteService.count()" },
                "expected noteService.count() in candidate values: ${members.map { it.value() }}",
            )
            assertTrue(
                members.any { it.displ() == "count()" },
                "expected clean display name count() in: ${members.map { it.displ() }}",
            )

            // Verify JLine's completion matcher matches the candidates against the input word
            val matcher = org.jline.reader.impl.CompletionMatcherImpl()
            val completingLine = parsed as org.jline.reader.CompletingParsedLine
            matcher.compile(emptyMap(), false, completingLine, false, 0, null)
            val matches = matcher.matches(members)
            assertTrue(
                matches.any { it.value() == "noteService.count()" },
                "expected noteService.count() to match noteService., got: ${matches.map { it.value() }}",
            )

            // Verify typing a prefix filters the members
            val filtered = mutableListOf<org.jline.reader.Candidate>()
            val parsedPrefix = reader.parser.parse("noteService.cou", 15, org.jline.reader.Parser.ParseContext.COMPLETE)
            completer.complete(reader, parsedPrefix, filtered)
            assertEquals(listOf("noteService.count()"), filtered.map { it.value() })

            val roots = mutableListOf<org.jline.reader.Candidate>()
            completer.complete(null, FakeParsedLine("over"), roots)
            assertTrue(roots.any { it.value() == "overdue" }, "got: ${roots.map { it.value() }}")
        } finally {
            repl.close()
            reader.terminal.close()
        }
    }

    @Test
    fun `a compile error is reported with a caret and the session continues`() {
        val output = runSession("noteService.noSuchMethod()", "\"still alive\"", ":quit")

        assertTrue(output.contains("compile error"), output)
        assertTrue(output.contains("^"), output)
        assertTrue(output.contains("still alive"), "the session must survive a compile error:\n$output")
    }

    @Test
    fun `a scripted session can declare Java-style variables and reference them`() {
        val output = runSession(
            "private String x;",
            "x = \"test-value\"",
            "x",
            ":quit",
        )

        assertTrue(output.contains("test-value"), "session should reflect assigned Java variable value:\n$output")
        assertTrue(!output.contains("compile error"), "should not have compile error:\n$output")
    }

    @Test
    fun `declaring a domain entity variable registers it and completes members on dot`() {
        val repl = ConsoleRepl(
            consoleSupplier = { console },
            reloadHandler = null,
            terminalFactory = { CapturingTerminal().terminal },
            requireInteractiveTerminal = false,
            historyFile = null,
        )
        try {
            val variables = linkedSetOf<String>()
            val variableTypes = java.util.concurrent.ConcurrentHashMap<String, Class<*>>()
            val reader = repl.createReader(CapturingTerminal().terminal, variables, variableTypes)
            val completer = repl.createCompleter(variables, variableTypes)

            // Evaluate declaration: "Note n = noteRepository.findAll().first()"
            val evalRes = console.eval("Note n = noteRepository.findAll().first()")
            assertEquals(io.github.springconsole.api.EvalStatus.SUCCESS, evalRes.status)

            // Invoke trackDeclarations
            repl.trackDeclarations(variables, variableTypes, "Note n = noteRepository.findAll().first()", evalRes)

            assertTrue("n" in variables, "variable 'n' should be tracked")
            assertEquals(Note::class.java, variableTypes["n"], "variable 'n' type should be Note")

            val candidates = mutableListOf<org.jline.reader.Candidate>()
            completer.complete(reader, FakeParsedLine("n."), candidates)
            val values = candidates.map { it.value() }

            assertTrue(values.contains("n.getTitle()"), "candidates should contain n.getTitle(): $values")
            assertTrue(values.contains("n.title"), "candidates should contain n.title: $values")
        } finally {
            repl.close()
        }
    }

    @Test
    fun `the reader binds arrow navigation keys and dot completion widget`() {
        val terminal = CapturingTerminal()
        val repl = ConsoleRepl(
            consoleSupplier = { console },
            terminalFactory = { terminal.terminal },
            requireInteractiveTerminal = false,
            historyFile = null,
        )

        val reader = repl.createReader(terminal.terminal, linkedSetOf("noteService"))
        try {
            val keyMap = reader.keyMaps[org.jline.reader.LineReader.MAIN]
            assertTrue(keyMap != null, "MAIN keymap must exist")
            assertTrue(keyMap.getBound("\u001b[A") != null, "Up arrow must be bound")
            assertTrue(keyMap.getBound("\u001b[B") != null, "Down arrow must be bound")
            assertTrue(keyMap.getBound("\u001b[D") != null, "Left arrow must be bound")
            assertTrue(keyMap.getBound("\u001b[C") != null, "Right arrow must be bound")
            assertTrue(keyMap.getBound(".") != null, "Dot must be bound for completion")
        } finally {
            repl.close()
            reader.terminal.close()
        }
    }
}
