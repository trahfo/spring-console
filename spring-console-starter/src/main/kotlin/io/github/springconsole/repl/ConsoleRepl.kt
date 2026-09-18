package io.github.springconsole.repl

import io.github.springconsole.ConsoleService
import io.github.springconsole.api.EvalResult
import io.github.springconsole.api.EvalStatus
import io.github.springconsole.api.ReloadResult
import io.github.springconsole.api.ReloadStatus
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.AttributedString
import org.jline.utils.AttributedStyle
import org.slf4j.LoggerFactory

/**
 * The human half of the console: an interactive JLine3 REPL on the attached
 * terminal. It shares every capability with the MCP interface by delegating to
 * the same [ConsoleService].
 *
 * Only starts when the process has a real (non-dumb) terminal, so it stays
 * silent in CI, tests, and daemonized deployments.
 */
class ConsoleRepl(
    private val consoleSupplier: () -> ConsoleService?,
    private val reloadHandler: ((recompile: Boolean) -> ReloadResult)? = null,
    defaultRollback: Boolean = true,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(ConsoleRepl::class.java)

    @Volatile
    private var running = false

    @Volatile
    private var rollback = defaultRollback

    private var thread: Thread? = null
    private var terminal: Terminal? = null

    /** Starts the REPL when an interactive terminal is available; returns whether it started. */
    fun startIfInteractive(): Boolean {
        val term = try {
            TerminalBuilder.builder().system(true).dumb(true).build()
        } catch (e: Exception) {
            log.debug("No terminal available, REPL not started", e)
            return false
        }
        if (term.type == Terminal.TYPE_DUMB || term.type == Terminal.TYPE_DUMB_COLOR) {
            log.info("No interactive terminal detected; Spring Console REPL not started (MCP endpoint unaffected)")
            term.close()
            return false
        }
        terminal = term
        running = true
        thread = Thread({ loop(term) }, "spring-console-repl").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun loop(terminal: Terminal) {
        val reader = LineReaderBuilder.builder()
            .terminal(terminal)
            .appName("spring-console")
            .variable(LineReader.HISTORY_FILE, System.getProperty("user.home") + "/.spring-console-history")
            .build()

        printBanner(terminal)

        while (running) {
            val line = try {
                reader.readLine(prompt())
            } catch (e: UserInterruptException) {
                continue
            } catch (e: EndOfFileException) {
                info(terminal, "Console detached (application keeps running). Reattach by restarting the app in a terminal.")
                break
            } catch (e: Exception) {
                if (running) log.debug("REPL read failed", e)
                break
            }

            val input = buildString {
                append(line)
                // naive continuation: keep reading while brackets are unbalanced
                while (!balanced(toString())) {
                    append('\n').append(reader.readLine("  ... "))
                }
            }.trim()

            if (input.isEmpty()) continue

            try {
                if (input.startsWith(":")) {
                    if (!command(terminal, input)) break
                } else {
                    render(terminal, evalOrWarn(terminal, input) ?: continue)
                }
            } catch (e: Exception) {
                error(terminal, "${e.javaClass.simpleName}: ${e.message}")
            }
        }
        running = false
    }

    private fun evalOrWarn(terminal: Terminal, code: String): EvalResult? {
        val console = consoleSupplier()
        if (console == null) {
            error(terminal, "Application context is not available (restarting?). Try again shortly.")
            return null
        }
        return console.eval(code, rollback)
    }

    /** @return false when the REPL should exit. */
    private fun command(terminal: Terminal, input: String): Boolean {
        val parts = input.split(Regex("\\s+"), limit = 2)
        val argument = parts.getOrNull(1)?.trim()
        when (parts[0]) {
            ":help", ":h" -> printHelp(terminal)
            ":quit", ":q", ":exit" -> {
                info(terminal, "Console detached (application keeps running).")
                return false
            }
            ":rollback" -> when (argument) {
                "on" -> { rollback = true; info(terminal, "Rollback sandbox ON — evaluations revert all transactional changes.") }
                "off" -> { rollback = false; info(terminal, "Rollback sandbox OFF — evaluations persist their changes.") }
                else -> info(terminal, "Rollback sandbox is ${if (rollback) "ON" else "OFF"}. Usage: :rollback on|off")
            }
            ":beans" -> {
                val console = consoleSupplier() ?: return true.also { error(terminal, "Context unavailable.") }
                val beans = console.listBeans(packageFilter = argument)
                beans.forEach { bean ->
                    terminal.writer().println(
                        "  ${bean.replName.padEnd(40)} ${bean.type}${if (bean.proxied) " (proxied)" else ""}",
                    )
                }
                info(terminal, "${beans.size} beans bound. Use them by name in snippets.")
            }
            ":inspect" -> {
                if (argument.isNullOrBlank()) {
                    info(terminal, "Usage: :inspect <beanName>")
                    return true
                }
                val console = consoleSupplier() ?: return true.also { error(terminal, "Context unavailable.") }
                val details = console.inspectBean(argument)
                if (details == null) {
                    error(terminal, "No bean named '$argument' (try :beans)")
                } else {
                    terminal.writer().println("  ${details.targetType} (bean '${details.name}', repl name '${details.replName}')")
                    details.methods.forEach { m ->
                        val params = m.parameters.joinToString(", ") { "${it.name}: ${it.type}" }
                        terminal.writer().println("    .${m.name}($params): ${m.returnType}")
                    }
                }
            }
            ":schema" -> {
                val console = consoleSupplier() ?: return true.also { error(terminal, "Context unavailable.") }
                val schema = console.contextSchema()
                schema.entities.forEach { entity ->
                    terminal.writer().println("  entity ${entity.name} (${entity.type})")
                    entity.attributes.forEach { a ->
                        terminal.writer().println("    ${a.name}: ${a.type}${if (a.id) " [id]" else ""}")
                    }
                }
                schema.repositories.forEach { repo ->
                    terminal.writer().println("  repository ${repo.replName}: ${repo.type} -> ${repo.domainType ?: "?"}")
                }
                schema.services.forEach { service ->
                    terminal.writer().println("  service ${service.replName}: ${service.type}")
                }
            }
            ":reload" -> {
                val handler = reloadHandler
                if (handler == null) {
                    error(terminal, "Reload is not available in this runtime.")
                    return true
                }
                info(terminal, "Recompiling and restarting context…")
                val result = handler(argument != "--no-compile")
                when (result.status) {
                    ReloadStatus.SUCCESS -> success(terminal, result.message)
                    else -> {
                        error(terminal, result.message)
                        result.compilationErrors?.forEach { e ->
                            error(terminal, "  ${e.line}:${e.column} ${e.message}")
                        }
                    }
                }
            }
            else -> info(terminal, "Unknown command ${parts[0]} (try :help)")
        }
        return true
    }

    private fun render(terminal: Terminal, result: EvalResult) {
        if (result.printedOutput.isNotEmpty()) {
            terminal.writer().print(result.printedOutput)
            if (!result.printedOutput.endsWith("\n")) terminal.writer().println()
        }
        when (result.status) {
            EvalStatus.SUCCESS -> {
                val rendered = result.result ?: return flushWithSandboxNote(terminal, result)
                success(terminal, "=> $rendered")
                flushWithSandboxNote(terminal, result)
            }
            EvalStatus.COMPILATION_ERROR -> result.compilationErrors?.forEach { e ->
                error(terminal, "compile error [${e.line}:${e.column}] ${e.message}")
            }
            EvalStatus.RUNTIME_EXCEPTION -> {
                val ex = result.exception
                error(terminal, "${ex?.type}: ${ex?.message}")
                ex?.stackTrace?.forEach { frame -> dim(terminal, "  $frame") }
            }
            EvalStatus.TIMEOUT -> error(terminal, result.result ?: "Evaluation timed out")
        }
    }

    private fun flushWithSandboxNote(terminal: Terminal, result: EvalResult) {
        if (result.transactionRolledBack) {
            dim(terminal, "   (rolled back in ${result.executionTimeMs}ms — :rollback off to persist)")
        }
        terminal.flush()
    }

    private fun prompt(): String {
        val marker = if (rollback) "" else "!"
        return AttributedString(
            "spring-console$marker> ",
            AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).bold(),
        ).toAnsi(terminal)
    }

    private fun printBanner(terminal: Terminal) {
        success(terminal, "Spring Console — Kotlin REPL bound to this application context")
        info(terminal, "Type Kotlin code to evaluate it, or :help for commands. Sandbox rollback is ${if (rollback) "ON" else "OFF"}.")
    }

    private fun printHelp(terminal: Terminal) {
        terminal.writer().println(
            """
            Spring Console commands:
              :help                 show this help
              :beans [package]      list beans bound into the REPL scope
              :inspect <bean>       show a bean's methods and types
              :schema               dump entities, repositories, and services
              :reload [--no-compile] recompile sources and hot-restart the context
              :rollback on|off      toggle the transactional sandbox (currently ${if (rollback) "ON" else "OFF"})
              :quit                 detach the console (application keeps running)

            Anything else is evaluated as Kotlin against the live context, e.g.:
              todoService.findAll()
              val overdue = todoRepository.count()
            """.trimIndent(),
        )
        terminal.flush()
    }

    private fun success(terminal: Terminal, message: String) = colored(terminal, message, AttributedStyle.GREEN)
    private fun info(terminal: Terminal, message: String) = colored(terminal, message, AttributedStyle.CYAN)
    private fun error(terminal: Terminal, message: String) = colored(terminal, message, AttributedStyle.RED)
    private fun dim(terminal: Terminal, message: String) = colored(terminal, message, AttributedStyle.BRIGHT)

    private fun colored(terminal: Terminal, message: String, color: Int) {
        terminal.writer().println(
            AttributedString(message, AttributedStyle.DEFAULT.foreground(color)).toAnsi(terminal),
        )
        terminal.flush()
    }

    private fun balanced(code: String): Boolean {
        var round = 0
        var square = 0
        var curly = 0
        var inString = false
        var inChar = false
        var escaped = false
        for (c in code) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                inString -> if (c == '"') inString = false
                inChar -> if (c == '\'') inChar = false
                c == '"' -> inString = true
                c == '\'' -> inChar = true
                c == '(' -> round++
                c == ')' -> round--
                c == '[' -> square++
                c == ']' -> square--
                c == '{' -> curly++
                c == '}' -> curly--
            }
        }
        return round <= 0 && square <= 0 && curly <= 0 && !inString
    }

    fun stop() {
        running = false
        thread?.interrupt()
        terminal?.close()
        terminal = null
        thread = null
    }

    override fun close() = stop()
}
