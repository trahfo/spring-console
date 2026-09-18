package io.github.springconsole.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.springconsole.ConsoleService
import io.github.springconsole.SpringConsoleProperties
import io.github.springconsole.fixture.ConsoleTestApp
import io.github.springconsole.fixture.NoteRepository
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
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
import kotlin.test.assertTrue

@SpringBootTest(
    classes = [ConsoleTestApp::class],
    properties = ["spring.datasource.url=jdbc:h2:mem:mcp-it;DB_CLOSE_DELAY=-1"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpServerIntegrationTest {

    @Autowired
    lateinit var context: ConfigurableApplicationContext

    @Autowired
    lateinit var noteRepository: NoteRepository

    private val mapper = jacksonObjectMapper()
    private lateinit var console: ConsoleService
    private lateinit var transport: McpHttpTransport
    private val client: HttpClient = HttpClient.newHttpClient()

    private val endpoint: String
        get() = "http://127.0.0.1:${transport.boundPort}/mcp"

    @BeforeAll
    fun startServer() {
        console = ConsoleService(context, SpringConsoleProperties())
        val tools = ConsoleTools(mapper, consoleSupplier = { console })
        transport = McpHttpTransport("127.0.0.1", 0, "/mcp", McpServer(mapper, tools))
        transport.start()
    }

    @AfterAll
    fun stopServer() {
        transport.stop()
        console.close()
    }

    private fun post(body: String): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun rpc(method: String, params: String = "{}", id: Int = 1): JsonNode {
        val response = post("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""")
        assertEquals(200, response.statusCode(), "body: ${response.body()}")
        return mapper.readTree(response.body())
    }

    private fun callTool(name: String, arguments: String = "{}"): JsonNode {
        val envelope = rpc("tools/call", """{"name":"$name","arguments":$arguments}""")
        val result = envelope.path("result")
        assertTrue(!result.isMissingNode, "expected result, got: $envelope")
        return result
    }

    @Test
    fun `initialize negotiates protocol version and reports server info`() {
        val result = rpc(
            "initialize",
            """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"0"}}""",
        ).path("result")

        assertEquals("2025-06-18", result.path("protocolVersion").asText())
        assertEquals("spring-console", result.path("serverInfo").path("name").asText())
        assertTrue(result.path("capabilities").has("tools"))
        assertTrue(result.path("instructions").asText().contains("eval"))
    }

    @Test
    fun `initialize downgrades unknown protocol versions to the newest supported`() {
        val result = rpc("initialize", """{"protocolVersion":"9999-01-01"}""").path("result")
        assertEquals("2025-06-18", result.path("protocolVersion").asText())
    }

    @Test
    fun `notifications are acknowledged with 202 and no body`() {
        val response = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(202, response.statusCode())
        assertTrue(response.body().isEmpty())
    }

    @Test
    fun `tools list exposes the five console tools with schemas`() {
        val tools = rpc("tools/list").path("result").path("tools")
        val names = tools.map { it.path("name").asText() }
        assertEquals(listOf("eval", "list_beans", "inspect_bean", "reload", "get_context_schema"), names)

        val eval = tools.first { it.path("name").asText() == "eval" }
        assertEquals("object", eval.path("inputSchema").path("type").asText())
        assertTrue(eval.path("inputSchema").path("properties").has("code"))
        assertEquals("code", eval.path("inputSchema").path("required")[0].asText())
    }

    @Test
    fun `eval tool executes code and returns a structured EvalResult`() {
        val result = callTool("eval", """{"code":"21 * 2"}""")

        assertEquals(false, result.path("isError").asBoolean())
        val structured = result.path("structuredContent")
        assertEquals("SUCCESS", structured.path("status").asText())
        assertEquals("42", structured.path("result").asText())
        assertTrue(structured.path("transactionRolledBack").asBoolean(), "agent evals default to rollback")

        val text = result.path("content")[0].path("text").asText()
        assertTrue(text.contains("\"status\" : \"SUCCESS\""), "text content should be JSON: $text")
    }

    @Test
    fun `eval tool rolls back database mutations by default`() {
        val before = noteRepository.count()
        val structured = callTool("eval", """{"code":"noteService.add(\"via mcp\")"}""").path("structuredContent")
        assertEquals("SUCCESS", structured.path("status").asText())
        assertEquals(before, noteRepository.count())
    }

    @Test
    fun `eval tool reports structured compilation errors`() {
        val structured = callTool("eval", """{"code":"val x: Int = \"nope\""}""").path("structuredContent")
        assertEquals("COMPILATION_ERROR", structured.path("status").asText())
        val error = structured.path("compilationErrors")[0]
        assertEquals(1, error.path("line").asInt())
        assertTrue(error.path("column").asInt() > 0)
        assertTrue(error.path("message").asText().isNotBlank())
    }

    @Test
    fun `inspect_bean returns bean details or a helpful error`() {
        val found = callTool("inspect_bean", """{"beanName":"noteService"}""")
        assertEquals(false, found.path("isError").asBoolean())
        assertEquals(
            "io.github.springconsole.fixture.NoteService",
            found.path("structuredContent").path("targetType").asText(),
        )

        val missing = callTool("inspect_bean", """{"beanName":"nope"}""")
        assertEquals(true, missing.path("isError").asBoolean())
        assertTrue(missing.path("structuredContent").path("error").asText().contains("list_beans"))
    }

    @Test
    fun `list_beans honors the package filter`() {
        val structured = callTool("list_beans", """{"packageFilter":"io.github.springconsole.fixture"}""")
            .path("structuredContent")
        val types = structured.path("beans").map { it.path("type").asText() }
        assertTrue(types.isNotEmpty())
        assertTrue(types.all { it.startsWith("io.github.springconsole.fixture") }, "got: $types")
    }

    @Test
    fun `get_context_schema returns the domain model`() {
        val structured = callTool("get_context_schema").path("structuredContent")
        assertEquals("Note", structured.path("entities")[0].path("name").asText())
        assertNotNull(structured.path("repositories")[0])
    }

    @Test
    fun `reload without a handler reports NOT_SUPPORTED`() {
        val structured = callTool("reload").path("structuredContent")
        assertEquals("NOT_SUPPORTED", structured.path("status").asText())
    }

    @Test
    fun `unknown methods return JSON-RPC method-not-found`() {
        val envelope = rpc("no/such/method")
        assertEquals(-32601, envelope.path("error").path("code").asInt())
    }

    @Test
    fun `unknown tools return invalid-params errors`() {
        val envelope = rpc("tools/call", """{"name":"bogus_tool"}""")
        assertEquals(-32602, envelope.path("error").path("code").asInt())
    }

    @Test
    fun `malformed json returns a parse error`() {
        val response = post("{not json")
        assertEquals(200, response.statusCode())
        assertEquals(-32700, mapper.readTree(response.body()).path("error").path("code").asInt())
    }

    @Test
    fun `GET requests are rejected`() {
        val response = client.send(
            HttpRequest.newBuilder(URI.create(endpoint)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(405, response.statusCode())
    }

    @Test
    fun `non-local origins are rejected`() {
        val response = client.send(
            HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Origin", "https://evil.example.com")
                .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":1,"method":"ping"}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(403, response.statusCode())
    }

    @Test
    fun `ping returns an empty result`() {
        val envelope = rpc("ping")
        assertTrue(envelope.path("result").isObject)
    }
}
