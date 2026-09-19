package io.github.springconsole.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.springconsole.ConsoleService
import io.github.springconsole.SpringConsoleProperties
import io.github.springconsole.api.EvalStatus
import io.github.springconsole.fixture.ConsoleTestApp
import io.github.springconsole.fixture.Note
import io.github.springconsole.fixture.NoteRepository
import io.github.springconsole.mcp.ConsoleTools
import io.github.springconsole.mcp.McpHttpTransport
import io.github.springconsole.mcp.McpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(
    classes = [ConsoleTestApp::class],
    properties = [
        "spring-console.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:remote-console-test;DB_CLOSE_DELAY=-1",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteConsoleOperationsTest {

    @Autowired
    lateinit var context: ConfigurableApplicationContext

    @Autowired
    lateinit var noteRepository: NoteRepository

    private val mapper = jacksonObjectMapper()
    private lateinit var console: ConsoleService
    private lateinit var transport: McpHttpTransport
    private lateinit var remoteOps: RemoteConsoleOperations

    @BeforeAll
    fun startServer() {
        console = ConsoleService(context, SpringConsoleProperties())
        val tools = ConsoleTools(mapper, consoleSupplier = { console })
        transport = McpHttpTransport("127.0.0.1", 0, "/mcp", McpServer(mapper, tools))
        transport.start()
        remoteOps = RemoteConsoleOperations("http://127.0.0.1:${transport.boundPort}/mcp")
    }

    @AfterAll
    fun stopServer() {
        transport.stop()
        console.close()
    }

    @Test
    fun `ping returns true when server is running`() {
        assertTrue(remoteOps.ping())
    }

    @Test
    fun `ping returns false when server is down`() {
        val deadOps = RemoteConsoleOperations("http://127.0.0.1:1/mcp")
        assertFalse(deadOps.ping())
    }

    @Test
    fun `eval executes code against the remote application context`() {
        val result = remoteOps.eval("noteRepository.count()")
        assertEquals(EvalStatus.SUCCESS, result.status)
        assertEquals("0", result.result)
    }

    @Test
    fun `eval handles compilation errors`() {
        val result = remoteOps.eval("nonExistentMethod()")
        assertEquals(EvalStatus.COMPILATION_ERROR, result.status)
        assertTrue(result.compilationErrors?.isNotEmpty() == true)
    }

    @Test
    fun `listBeans returns registered beans with their repl names`() {
        val beans = remoteOps.listBeans()
        assertTrue(beans.isNotEmpty())
        val noteRepoBean = beans.firstOrNull { it.name == "noteRepository" }
        assertNotNull(noteRepoBean)
        assertEquals("noteRepository", noteRepoBean.replName)
    }

    @Test
    fun `inspectBean returns methods and properties`() {
        val details = remoteOps.inspectBean("noteRepository")
        assertNotNull(details)
        assertEquals("noteRepository", details.name)
        assertTrue(details.methods.any { it.name == "count" })
    }

    @Test
    fun `contextSchema returns entities and repositories`() {
        val schema = remoteOps.contextSchema()
        assertTrue(schema.entities.any { it.name == "Note" })
        assertTrue(schema.repositories.any { it.replName == "noteRepository" })
    }

    @Test
    fun `resolveClassBySimpleName resolves entity class`() {
        val clazz = remoteOps.resolveClassBySimpleName("Note")
        assertNotNull(clazz)
        assertEquals(Note::class.java, clazz)
    }

    @Test
    fun `a full REPL session runs over RemoteConsoleOperations`() {
        val terminal = io.github.springconsole.repl.CapturingTerminal(
            listOf(
                ":beans",
                "noteRepository.count()",
                ":quit",
            ).joinToString(separator = "\n", postfix = "\n"),
        )
        val repl = io.github.springconsole.repl.ConsoleRepl(
            consoleSupplier = { remoteOps },
            terminalFactory = { terminal.terminal },
            requireInteractiveTerminal = false,
            historyFile = null,
        )

        assertTrue(repl.startIfInteractive(), "the REPL should start on a scripted terminal")
        assertTrue(repl.awaitTermination(30_000), "the REPL loop did not finish\n${terminal.text()}")
        repl.close()

        val text = terminal.text()
        assertTrue(text.contains("Spring Console"), "expected banner in output: $text")
        assertTrue(text.contains("noteRepository"), "expected bean listing: $text")
        assertTrue(text.contains("0"), "expected evaluation result: $text")
        assertTrue(text.contains("Console detached"), "expected detach message: $text")
    }
}
