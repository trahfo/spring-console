package io.github.springconsole.mcp

import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/**
 * Serves MCP over stdin/stdout: one JSON-RPC message per line, per the MCP
 * stdio transport. Intended for `spring-console.mcp.stdio=true`, where an MCP
 * client launches the whole Spring Boot app as a subprocess.
 *
 * Standard streams are captured once at start so snippet output capture and
 * application logging cannot corrupt the protocol channel. Hosts must route
 * their logging away from stdout (e.g. to stderr or a file) in stdio mode.
 */
class McpStdioTransport(private val server: McpServer) : AutoCloseable {

    private val log = LoggerFactory.getLogger(McpStdioTransport::class.java)

    @Volatile
    private var running = false

    private var thread: Thread? = null

    fun start() {
        val stdin = BufferedReader(InputStreamReader(System.`in`, StandardCharsets.UTF_8))
        val stdout = PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8)
        running = true
        thread = Thread({
            log.info("Spring Console MCP server listening on stdio")
            while (running) {
                val line = try {
                    stdin.readLine() ?: break
                } catch (e: Exception) {
                    break
                }
                if (line.isBlank()) continue
                val response = try {
                    server.handle(line)
                } catch (e: Exception) {
                    log.error("stdio MCP message failed", e)
                    null
                }
                if (response != null) {
                    synchronized(stdout) {
                        stdout.println(response)
                    }
                }
            }
            log.info("Spring Console MCP stdio loop ended")
        }, "spring-console-mcp-stdio").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    override fun close() = stop()
}
