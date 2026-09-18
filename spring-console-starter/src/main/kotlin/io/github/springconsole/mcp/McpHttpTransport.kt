package io.github.springconsole.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serves the MCP endpoint over the streamable HTTP transport using the JDK's
 * built-in [HttpServer] — deliberately independent of the host application's
 * web stack (works in non-web apps) and of its lifecycle (survives context
 * restarts during `reload`, so the agent's connection never drops).
 *
 * Binds to localhost by default. The console executes arbitrary code;
 * exposing it on a non-loopback interface is a remote-code-execution hole.
 */
class McpHttpTransport(
    private val host: String,
    private val port: Int,
    private val path: String,
    private val server: McpServer,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(McpHttpTransport::class.java)

    private var httpServer: HttpServer? = null

    val boundPort: Int
        get() = httpServer?.address?.port ?: port

    fun start() {
        val threadCounter = AtomicInteger(1)
        val executor = Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "spring-console-mcp-${threadCounter.getAndIncrement()}").apply { isDaemon = true }
        }
        httpServer = HttpServer.create(InetSocketAddress(host, port), 0).apply {
            createContext(path) { exchange -> handleSafely(exchange) }
            setExecutor(executor)
            start()
        }
        log.info("Spring Console MCP server listening on http://{}:{}{}", host, boundPort, path)
    }

    private fun handleSafely(exchange: HttpExchange) {
        try {
            handle(exchange)
        } catch (e: Exception) {
            log.error("MCP transport error", e)
            try {
                respond(exchange, 500, """{"error":"internal transport error"}""")
            } catch (ignored: Exception) {
            }
        } finally {
            exchange.close()
        }
    }

    private fun handle(exchange: HttpExchange) {
        if (!originAllowed(exchange)) {
            respond(exchange, 403, """{"error":"origin not allowed"}""")
            return
        }
        when (exchange.requestMethod) {
            "POST" -> {
                val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                val response = server.handle(body)
                if (response == null) {
                    // Notification: acknowledged, no body.
                    exchange.sendResponseHeaders(202, -1)
                } else {
                    respond(exchange, 200, response)
                }
            }
            // This server responds inline to POSTs and offers no server-initiated stream.
            "GET" -> respond(exchange, 405, """{"error":"SSE streaming is not supported; POST JSON-RPC messages"}""")
            "DELETE" -> exchange.sendResponseHeaders(204, -1) // stateless: nothing to terminate
            else -> respond(exchange, 405, """{"error":"method not allowed"}""")
        }
    }

    /**
     * DNS-rebinding protection per the MCP spec: browser-originated requests
     * must come from a local origin. Non-browser clients send no Origin.
     */
    private fun originAllowed(exchange: HttpExchange): Boolean {
        val origin = exchange.requestHeaders.getFirst("Origin") ?: return true
        val originHost = try {
            URI(origin).host ?: return false
        } catch (e: Exception) {
            return false
        }
        return originHost == "localhost" || originHost == "127.0.0.1" || originHost == "::1" || originHost == host
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    fun stop() {
        httpServer?.stop(0)
        httpServer = null
        log.info("Spring Console MCP server stopped")
    }

    override fun close() = stop()
}
