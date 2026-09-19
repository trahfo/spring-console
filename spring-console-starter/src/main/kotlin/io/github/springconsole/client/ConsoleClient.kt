package io.github.springconsole.client

import io.github.springconsole.repl.ConsoleRepl
import kotlin.system.exitProcess

/**
 * Interactive CLI client that attaches to a running Spring Console MCP server over HTTP.
 *
 * This provides the exact same human terminal REPL (JLine3 with syntax highlighting,
 * completion, colorized tables, and :commands) without starting a new Spring Boot application
 * or opening new server ports.
 */
object ConsoleClient {

    @JvmStatic
    fun main(args: Array<String>) {
        val url = args.firstOrNull { !it.startsWith("-") } ?: "http://127.0.0.1:8085/mcp"
        val remoteOps = RemoteConsoleOperations(mcpUrl = url)

        if (!remoteOps.ping()) {
            System.err.println("Error: Could not connect to Spring Console at $url.")
            System.err.println("Ensure your Spring Boot application is running with spring-console.")
            exitProcess(1)
        }

        val repl = ConsoleRepl(
            consoleSupplier = { remoteOps },
            reloadHandler = { recompile -> remoteOps.reload(recompile) },
            requireInteractiveTerminal = false,
        )

        val started = repl.startIfInteractive()
        if (!started) {
            exitProcess(1)
        }

        repl.awaitTermination(Long.MAX_VALUE)
        exitProcess(0)
    }
}
