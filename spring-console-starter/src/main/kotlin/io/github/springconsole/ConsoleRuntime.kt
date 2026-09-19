package io.github.springconsole

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.springconsole.api.ReloadResult
import io.github.springconsole.api.ReloadStatus
import io.github.springconsole.mcp.ConsoleTools
import io.github.springconsole.mcp.McpHttpTransport
import io.github.springconsole.mcp.McpServer
import io.github.springconsole.mcp.McpStdioTransport
import io.github.springconsole.reload.CompilationBridge
import io.github.springconsole.reload.ReloadService
import io.github.springconsole.reload.RestartContext
import io.github.springconsole.reload.Restarter
import io.github.springconsole.repl.ConsoleRepl
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContext
import java.io.File
import java.util.concurrent.CompletableFuture

/**
 * Process-wide console runtime. The interface layer (MCP transports, terminal
 * REPL) and the reload machinery deliberately live *outside* the Spring
 * ApplicationContext: a `reload` closes and replaces the context, and the
 * transport that carried the reload request must survive to deliver its
 * response. Each context attaches its [ConsoleService] here on startup and
 * detaches on close; the transports simply follow the current attachment.
 */
object ConsoleRuntime : Restarter.RestartCoordinator {

    private val log = LoggerFactory.getLogger(ConsoleRuntime::class.java)

    @Volatile
    private var current: ConsoleService? = null

    @Volatile
    private var restarting = false

    @Volatile
    private var pendingAttach: CompletableFuture<ConsoleService>? = null

    private var transportsStarted = false
    private var httpTransport: McpHttpTransport? = null
    private var stdioTransport: McpStdioTransport? = null
    private var repl: ConsoleRepl? = null
    private var reloadService: ReloadService? = null

    /** The console for the currently attached (live) context, if any. */
    fun console(): ConsoleService? = current

    /** The bound MCP HTTP port, when the HTTP transport is running. */
    fun mcpPort(): Int? = httpTransport?.boundPort

    @Synchronized
    fun attach(console: ConsoleService, restartContext: RestartContext?) {
        val previous = current
        if (previous != null && previous.context !== console.context) {
            previous.close()
        }
        current = console
        pendingAttach?.complete(console)

        val properties = console.properties
        if (!transportsStarted) {
            transportsStarted = true
            startReloadMachinery(properties, restartContext)
            startTransports(properties)
        }
        console.warmUpAsync()
    }

    @Synchronized
    fun detach(context: ApplicationContext) {
        val attached = current ?: return
        if (attached.context !== context) return
        attached.close()
        current = null
        if (!restarting) {
            shutdownTransports()
        }
    }

    fun reload(recompile: Boolean): ReloadResult =
        reloadService?.reload(recompile)
            ?: ReloadResult(
                ReloadStatus.NOT_SUPPORTED,
                "Reload is not available: the application was not started from a main() method " +
                    "(tests and some launchers), or spring-console.reload.enabled=false.",
            )

    private fun startReloadMachinery(properties: SpringConsoleProperties, restartContext: RestartContext?) {
        if (!properties.reload.enabled || restartContext == null) return
        val projectDir = properties.reload.projectDir.ifBlank { System.getProperty("user.dir") }
        val bridge = CompilationBridge(
            projectDir = File(projectDir),
            configuredCommand = properties.reload.command,
            timeoutMs = properties.reload.compileTimeoutMs,
        )
        reloadService = ReloadService(
            bridge = bridge,
            restarter = Restarter(restartContext, this),
            restartTimeoutMs = properties.reload.restartTimeoutMs,
        )
    }

    private fun startTransports(properties: SpringConsoleProperties) {
        val mapper = jacksonObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)
        val tools = ConsoleTools(mapper, ::console) { recompile -> reload(recompile) }
        val server = McpServer(mapper, tools)

        if (properties.mcp.stdio) {
            stdioTransport = McpStdioTransport(server).also { it.start() }
            return // stdout belongs to the protocol; never start the REPL
        }

        if (properties.mcp.enabled) {
            try {
                httpTransport = McpHttpTransport(
                    properties.mcp.host,
                    properties.mcp.port,
                    properties.mcp.path,
                    server,
                ).also { it.start() }
            } catch (e: Exception) {
                log.warn(
                    "Could not start the MCP server on {}:{} ({}). The console REPL still works; " +
                        "set spring-console.mcp.port to a free port.",
                    properties.mcp.host,
                    properties.mcp.port,
                    e.message,
                )
                httpTransport = null
            }
        }

        if (properties.repl.enabled) {
            repl = ConsoleRepl(::console, { recompile -> reload(recompile) })
                .also { it.startIfInteractive() }
        }
    }

    @Synchronized
    private fun shutdownTransports() {
        httpTransport?.stop()
        httpTransport = null
        stdioTransport?.stop()
        stdioTransport = null
        repl?.stop()
        repl = null
        reloadService = null
        transportsStarted = false
    }

    // --- Restarter.RestartCoordinator ---

    override fun beginRestart(): CompletableFuture<ConsoleService> {
        restarting = true
        return CompletableFuture<ConsoleService>().also { pendingAttach = it }
    }

    override fun endRestart() {
        restarting = false
        pendingAttach = null
    }

    override fun currentConsole(): ConsoleService? = current

    /**
     * Tears the whole console runtime down: detaches the current context and
     * stops every transport. Mainly for tests and embedders that manage
     * multiple application contexts in one JVM.
     */
    @Synchronized
    fun shutdown() {
        current?.close()
        current = null
        restarting = false
        pendingAttach = null
        shutdownTransports()
    }
}
