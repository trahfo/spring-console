package io.github.springconsole.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serves the MCP endpoint over streamable HTTP and Server-Sent Events (SSE)
 * using the JDK's built-in [HttpServer] — deliberately independent of the host
 * application's web stack (works in non-web apps) and of its lifecycle
 * (survives context restarts during `reload`, so the agent's connection never drops).
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
    private val sseSessions = ConcurrentHashMap<String, SseSession>()

    val boundPort: Int
        get() = httpServer?.address?.port ?: port

    fun start() {
        val threadCounter = AtomicInteger(1)
        val executor = Executors.newFixedThreadPool(8) { runnable ->
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
        var isSse = false
        try {
            isSse = handle(exchange)
        } catch (t: Throwable) {
            log.error("MCP transport error", t)
            try {
                respond(exchange, 500, """{"error":"internal transport error: ${t.message}"}""")
            } catch (ignored: Throwable) {
            }
        } finally {
            if (!isSse) {
                try {
                    exchange.close()
                } catch (ignored: Throwable) {
                }
            }
        }
    }

    /**
     * @return true if this exchange was upgraded to a long-lived SSE stream.
     */
    private fun handle(exchange: HttpExchange): Boolean {
        if (!originAllowed(exchange)) {
            respond(exchange, 403, """{"error":"origin not allowed"}""")
            return false
        }

        // Add CORS response headers for allowed origins
        exchange.requestHeaders.getFirst("Origin")?.let { origin ->
            exchange.responseHeaders.add("Access-Control-Allow-Origin", origin)
            exchange.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type, Accept, Authorization")
            exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        }

        when (exchange.requestMethod) {
            "OPTIONS" -> {
                exchange.sendResponseHeaders(204, -1)
                return false
            }
            "GET" -> {
                val accept = exchange.requestHeaders.getFirst("Accept") ?: ""
                return if (accept.contains("text/event-stream")) {
                    handleSse(exchange)
                    true
                } else {
                    respond(exchange, 405, """{"error":"SSE streaming requires Accept: text/event-stream; POST JSON-RPC messages"}""")
                    false
                }
            }
            "POST" -> {
                val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                val sessionId = extractSessionId(exchange)
                val session = sessionId?.let { sseSessions[it] }

                val response = server.handle(body)

                if (session != null) {
                    // MCP SSE protocol: deliver the response on the SSE stream
                    if (response != null) {
                        session.send("message", response)
                    }
                    exchange.sendResponseHeaders(202, -1)
                } else {
                    // Streamable HTTP: inline response to POST
                    if (response == null) {
                        exchange.sendResponseHeaders(202, -1)
                    } else {
                        respond(exchange, 200, response)
                    }
                }
                return false
            }
            "DELETE" -> {
                val sessionId = extractSessionId(exchange)
                if (sessionId != null) {
                    sseSessions.remove(sessionId)?.close()
                }
                exchange.sendResponseHeaders(204, -1)
                return false
            }
            else -> {
                respond(exchange, 405, """{"error":"method not allowed"}""")
                return false
            }
        }
    }

    private fun handleSse(exchange: HttpExchange) {
        val sessionId = UUID.randomUUID().toString()
        val session = SseSession(sessionId, exchange)
        sseSessions[sessionId] = session

        try {
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.responseHeaders.add("Cache-Control", "no-cache")
            exchange.responseHeaders.add("Connection", "keep-alive")
            exchange.sendResponseHeaders(200, 0) // chunked response

            val endpointUri = "$path?sessionId=$sessionId"
            session.sendRaw("event: endpoint\ndata: $endpointUri\n\n")

            log.debug("MCP SSE client connected (sessionId: {})", sessionId)

            while (session.isOpen && httpServer != null && !Thread.currentThread().isInterrupted) {
                val event = session.queue.poll(15, TimeUnit.SECONDS)
                if (event != null) {
                    session.sendRaw(event)
                } else {
                    // SSE comment ping to keep connection alive
                    session.sendRaw(":\n\n")
                }
            }
        } catch (e: Exception) {
            log.debug("MCP SSE client disconnected (sessionId: {}): {}", sessionId, e.message)
        } finally {
            sseSessions.remove(sessionId)
            session.close()
            try {
                exchange.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun extractSessionId(exchange: HttpExchange): String? {
        val query = exchange.requestURI.query ?: return null
        return query.split("&")
            .map { it.split("=", limit = 2) }
            .firstOrNull { it[0] == "sessionId" && it.size > 1 }
            ?.get(1)
    }

    private class SseSession(
        val id: String,
        val exchange: HttpExchange,
    ) : AutoCloseable {
        @Volatile
        var isOpen = true
            private set

        val queue = LinkedBlockingQueue<String>()

        fun send(event: String, data: String) {
            if (!isOpen) return
            queue.offer("event: $event\ndata: $data\n\n")
        }

        fun sendRaw(raw: String) {
            if (!isOpen) return
            val bytes = raw.toByteArray(StandardCharsets.UTF_8)
            synchronized(exchange.responseBody) {
                exchange.responseBody.write(bytes)
                exchange.responseBody.flush()
            }
        }

        override fun close() {
            isOpen = false
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
        sseSessions.values.forEach { it.close() }
        sseSessions.clear()
        httpServer?.stop(0)
        httpServer = null
        log.info("Spring Console MCP server stopped")
    }

    override fun close() = stop()
}
