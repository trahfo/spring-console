package io.github.springconsole.autoconfigure

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.springconsole.ConsoleRuntime
import io.github.springconsole.api.EvalStatus
import io.github.springconsole.fixture.ConsoleTestApp
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@SpringBootTest(
    classes = [ConsoleTestApp::class],
    properties = [
        "spring-console.mcp.port=0",
        "spring-console.warmup=false",
        "spring.datasource.url=jdbc:h2:mem:autoconfig-it;DB_CLOSE_DELAY=-1",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpringConsoleAutoConfigurationTest {

    @Autowired
    lateinit var context: ConfigurableApplicationContext

    @AfterAll
    fun cleanup() {
        ConsoleRuntime.resetForTests()
    }

    @Test
    fun `the console attaches to the runtime when the context is ready`() {
        val console = assertNotNull(ConsoleRuntime.console(), "console should attach on ApplicationReadyEvent")
        assertSame(context, console.context)
    }

    @Test
    fun `evaluations work through the attached console`() {
        val result = ConsoleRuntime.console()!!.eval("noteService.count()")
        assertEquals(EvalStatus.SUCCESS, result.status, "unexpected: $result")
    }

    @Test
    fun `the MCP server is reachable over http`() {
        val port = assertNotNull(ConsoleRuntime.mcpPort(), "MCP transport should be running")
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/mcp"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""",
                    ),
                )
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode())
        val body = jacksonObjectMapper().readTree(response.body())
        assertEquals("spring-console", body.path("result").path("serverInfo").path("name").asText())
    }

    @Test
    fun `reload reports not supported in a test runtime`() {
        // Tests are not started from a main() method, so restart is unavailable by design.
        val result = ConsoleRuntime.reload(recompile = false)
        assertTrue(result.message.isNotBlank())
        assertEquals(io.github.springconsole.api.ReloadStatus.NOT_SUPPORTED, result.status)
    }
}
