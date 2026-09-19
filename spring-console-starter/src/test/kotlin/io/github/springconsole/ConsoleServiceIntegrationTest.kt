package io.github.springconsole

import io.github.springconsole.api.EvalStatus
import io.github.springconsole.fixture.ConsoleTestApp
import io.github.springconsole.fixture.NoteRepository
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(
    classes = [ConsoleTestApp::class],
    properties = [
        "spring-console.enabled=false", // this test drives its own ConsoleService instance
        "spring.datasource.url=jdbc:h2:mem:console-it;DB_CLOSE_DELAY=-1",
        "logging.level.org.hibernate=warn",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleServiceIntegrationTest {

    @Autowired
    lateinit var context: ConfigurableApplicationContext

    @Autowired
    lateinit var noteRepository: NoteRepository

    private val console: ConsoleService by lazy {
        ConsoleService(context, SpringConsoleProperties())
    }

    @AfterAll
    fun tearDown() {
        console.close()
    }

    @Test
    fun `mutations are persisted with permanent consequences`() {
        val result = console.eval("noteService.add(\"persistent note\").id")

        assertEquals(EvalStatus.SUCCESS, result.status, "unexpected: $result")
        assertEquals(1, noteRepository.findByTitle("persistent note").size)

        noteRepository.deleteAll()
    }

    @Test
    fun `repository beans are bound through their user interface despite JDK proxying`() {
        val result = console.eval("noteRepository.count()")
        assertEquals(EvalStatus.SUCCESS, result.status, "unexpected: $result")
        assertNotNull(result.result)
    }

    @Test
    fun `printed output is captured separately from the result`() {
        val result = console.eval("println(\"side channel\"); 7 * 6")
        assertEquals(EvalStatus.SUCCESS, result.status)
        assertEquals("42", result.result)
        assertTrue(result.printedOutput.contains("side channel"), "got: ${result.printedOutput}")
    }

    @Test
    fun `runtime exceptions carry pruned domain stack traces`() {
        val result = console.eval("noteRepository.findById(999999L).get()")
        assertEquals(EvalStatus.RUNTIME_EXCEPTION, result.status)
        val exception = assertNotNull(result.exception)
        assertEquals("java.util.NoSuchElementException", exception.type)
        assertTrue(
            exception.stackTrace.none { it.contains("org.springframework.aop") },
            "AOP frames must be pruned: ${exception.stackTrace}",
        )
    }

    @Test
    fun `timeouts interrupt the snippet and report TIMEOUT`() {
        val result = console.eval("Thread.sleep(60_000)", timeoutMs = 400)
        assertEquals(EvalStatus.TIMEOUT, result.status)

        val followUp = console.eval("\"alive\"")
        assertEquals(EvalStatus.SUCCESS, followUp.status, "console must recover after a timeout")
        assertEquals("alive", followUp.result)
    }

    @Test
    fun `listBeans exposes application beans with resolved types`() {
        val beans = console.listBeans(packageFilter = "io.github.springconsole.fixture")
        val service = beans.firstOrNull { it.name == "noteService" }
        val repository = beans.firstOrNull { it.name == "noteRepository" }

        assertNotNull(service, "noteService missing from: ${beans.map { it.name }}")
        assertEquals("io.github.springconsole.fixture.NoteService", service.type)
        assertTrue(service.proxied, "@Transactional service should be proxied")

        assertNotNull(repository, "noteRepository missing")
        assertEquals("io.github.springconsole.fixture.NoteRepository", repository.type)
    }

    @Test
    fun `inspectBean reports methods and unwrapped types`() {
        val details = assertNotNull(console.inspectBean("noteService"))
        assertEquals("io.github.springconsole.fixture.NoteService", details.targetType)
        assertTrue(details.proxied)
        assertTrue(details.methods.any { it.name == "add" }, "got: ${details.methods.map { it.name }}")
        assertTrue(details.runtimeType != details.targetType, "runtime type should be the proxy class")
    }

    @Test
    fun `context schema lists entities, repositories, and services`() {
        val schema = console.contextSchema()

        val note = schema.entities.firstOrNull { it.name == "Note" }
        assertNotNull(note, "Note entity missing: ${schema.entities}")
        assertTrue(note.attributes.any { it.name == "id" && it.id }, "id attribute: ${note.attributes}")

        val repository = schema.repositories.firstOrNull { it.type.endsWith("NoteRepository") }
        assertNotNull(repository, "NoteRepository missing: ${schema.repositories}")
        assertEquals("io.github.springconsole.fixture.Note", repository.domainType)
        assertTrue(repository.methods.any { it.startsWith("findByTitle") }, "got: ${repository.methods}")

        assertTrue(
            schema.services.any { it.type == "io.github.springconsole.fixture.NoteService" },
            "got: ${schema.services}",
        )
    }
}
