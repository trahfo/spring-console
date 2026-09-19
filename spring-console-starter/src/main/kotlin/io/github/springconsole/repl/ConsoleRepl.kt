package io.github.springconsole.repl

import io.github.springconsole.ConsoleOperations
import io.github.springconsole.api.EvalResult
import io.github.springconsole.api.EvalStatus
import io.github.springconsole.api.ReloadResult
import io.github.springconsole.engine.JavaDeclarationPreprocessor
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.Reference
import org.jline.reader.UserInterruptException
import org.jline.reader.Widget
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.AttributedString
import org.slf4j.LoggerFactory

/**
 * The human half of the console: an interactive JLine3 REPL on the attached
 * terminal. It shares every capability with the MCP interface by delegating to
 * the same [ConsoleService], so a value produced by typing is identical to a
 * value produced by an agent's `eval` tool call.
 *
 * ## Feature map
 *
 * | Feature              | Component                  |
 * |----------------------|----------------------------|
 * | syntax highlighting  | [KotlinSyntaxHighlighter]  |
 * | tab completion       | [ConsoleCompleter]         |
 * | pretty value view    | [ResultRenderer] + [ReplPresenter] |
 * | colored diagnostics  | [ReplTheme]                |
 * | `:commands`          | [ReplCommandHandler]       |
 *
 * ## Lifecycle
 *
 * [startIfInteractive] only starts the loop when the process actually has a
 * terminal, so the REPL stays silent in CI, tests, and daemonized deployments
 * (where the MCP endpoint is the only interface). The loop runs on a daemon
 * thread; `:quit` detaches the console without terminating the application, and
 * [stop] is called on context shutdown / reload.
 *
 * Both the terminal factory, the requirement for an interactive terminal, and
 * the history file are constructor parameters so tests can drive the REPL
 * deterministically.
 *
 * @param consoleSupplier supplies the console for the currently attached
 *   application context; null while a reload is in flight.
 * @param reloadHandler performs `:reload`; null when reload is unavailable.
 * @param terminalFactory builds the terminal (overridden in tests).
 * @param requireInteractiveTerminal when false, a dumb terminal is accepted
 *   (used by tests that feed a scripted terminal).
 * @param historyFile persisted JLine history; null disables history.
 */
class ConsoleRepl(
    private val consoleSupplier: () -> ConsoleOperations?,
    private val reloadHandler: ((recompile: Boolean) -> ReloadResult)? = null,
    private val terminalFactory: () -> Terminal = ::defaultConsoleTerminal,
    private val requireInteractiveTerminal: Boolean = true,
    private val historyFile: String? = defaultHistoryFile(),
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(ConsoleRepl::class.java)

    @Volatile
    private var running = false

    private var thread: Thread? = null
    private var terminal: Terminal? = null

    /** Starts the REPL when an interactive terminal is available; returns whether it started. */
    fun startIfInteractive(): Boolean {
        val term = try {
            terminalFactory()
        } catch (e: Exception) {
            log.debug("No terminal available, REPL not started", e)
            return false
        }
        if (requireInteractiveTerminal && (term.type == Terminal.TYPE_DUMB || term.type == Terminal.TYPE_DUMB_COLOR)) {
            log.info(
                "No interactive terminal detected; Spring Console REPL not started (MCP endpoint unaffected). " +
                    "Run the application attached to a terminal to use the REPL — with Gradle, either " +
                    "`./gradlew bootRun --no-daemon` or build and run the jar directly.",
            )
            // Deliberately not closed: a dumb terminal may wrap System.in/out,
            // and closing it would close the application's standard streams.
            return false
        }
        terminal = term
        running = true
        thread = Thread({ loop(term) }, "spring-console-repl").apply {
            // Non-daemon on purpose: the REPL is the process's foreground
            // interface while it runs. In a server app the web/MCP threads keep
            // the JVM alive regardless, but in a "console only" app
            // (`spring.main.web-application-type=none`) this is what prevents
            // the JVM from exiting the instant main() returns and the context is
            // ready. When the loop ends (`:quit`/Ctrl-D) the JVM stops
            // naturally if nothing else is running.
            isDaemon = false
            start()
        }
        return true
    }

    // ------------------------------------------------------------------
    // The loop
    // ------------------------------------------------------------------

    private fun loop(terminal: Terminal) {
        val output = ReplOutput(terminal)
        val presenter = ReplPresenter(output)
        val commands = ReplCommandHandler(output, consoleSupplier, reloadHandler)
        val variables = linkedSetOf<String>()
        val variableTypes = java.util.concurrent.ConcurrentHashMap<String, Class<*>>()
        val reader = createReader(terminal, variables, variableTypes)

        commands.printBanner()
        output.flush()

        while (running) {
            val line = try {
                reader.readLine(prompt(terminal))
            } catch (e: UserInterruptException) {
                continue
            } catch (e: EndOfFileException) {
                output.info("Console detached. End of input.")
                break
            } catch (e: Exception) {
                if (running) log.debug("REPL read failed", e)
                break
            }

            val input = readCompleteSnippet(reader, line).trim()
            if (input.isEmpty()) continue

            try {
                if (input.startsWith(":")) {
                    if (!commands.handle(input)) break
                } else {
                    val result = evalOrWarn(output, input) ?: continue
                    presenter.present(result, input)
                    trackDeclarations(variables, variableTypes, input, result)
                }
            } catch (e: Exception) {
                output.error("${e.javaClass.simpleName}: ${e.message}")
            }
        }
        try {
            reader.history.save()
        } catch (e: Exception) {
            log.debug("Failed to save REPL history", e)
        }
        running = false
        releaseTerminal()
    }

    /**
     * Releases the terminal when the REPL session ends (`:quit`, Ctrl-D, or a
     * read failure).
     *
     * Intentionally neither `close()`s nor `pause()`s it:
     *
     * - `close()` makes JLine's blocked posix I/O pump print a misleading
     *   `IOException: Stream Closed` stack trace, and doing it from the JVM
     *   shutdown hook (via [stop]) prevents the process from exiting at all —
     *   which breaks console-only applications;
     * - `pause()` can stop the output pump before it has forwarded the final
     *   "Console detached." line.
     *
     * JLine has already restored the terminal attributes after the last read,
     * and its remaining I/O pump threads are daemons, so nothing here keeps the
     * JVM alive: the process exits (in a web app: keeps serving) as soon as the
     * REPL thread finishes.
     */
    private fun releaseTerminal() {
        terminal = null
    }

    internal fun createCompleter(
        variables: MutableSet<String>,
        variableTypes: MutableMap<String, Class<*>> = mutableMapOf(),
    ): ConsoleCompleter =
        ConsoleCompleter(
            beanNames = { boundBeanNames() },
            inspectBean = { name -> consoleSupplier()?.inspectBean(name) },
            sessionVariables = { variables.toSet() },
            inspectVariable = { name ->
                val type = variableTypes[name]
                if (type != null) {
                    consoleSupplier()?.inspectClass(name, type)
                } else null
            },
        )

    /**
     * Builds the JLine line editor for [terminal], wiring in the interactive features:
     *
     * - [ConsoleCompleter] — Tab and dot completion for commands, beans, members, and
     *   earlier session variables (with `AUTO_LIST`/`AUTO_MENU` for discovery),
     * - [KotlinSyntaxHighlighter] — live coloring of the input line,
     * - Keybindings for arrow history navigation (Up/Down) and cursor/word navigation (Left/Right),
     * - Automatic member completion on typing '.' after a bean or variable.
     */
    internal fun createReader(
        terminal: Terminal,
        variables: MutableSet<String>,
        variableTypes: MutableMap<String, Class<*>> = mutableMapOf(),
    ): LineReader {
        val completer = createCompleter(variables, variableTypes)
        val highlighter = KotlinSyntaxHighlighter()

        val history = org.jline.reader.impl.history.DefaultHistory()
        val builder = LineReaderBuilder.builder()
            .terminal(terminal)
            .appName("spring-console")
            .completer(completer)
            .highlighter(highlighter)
            .history(history)
            .option(LineReader.Option.AUTO_LIST, true)
            .option(LineReader.Option.AUTO_MENU, true)
            .option(LineReader.Option.COMPLETE_IN_WORD, true)
            .option(LineReader.Option.LIST_PACKED, true)
            .option(LineReader.Option.INSERT_TAB, false)
            .variable(LineReader.LIST_MAX, 500)
        historyFile?.let { builder.variable(LineReader.HISTORY_FILE, it) }
        val reader = builder.build()

        val mainKeyMap = reader.keyMaps[LineReader.MAIN]
        if (mainKeyMap != null) {
            // Arrow keys: Up/Down for history
            mainKeyMap.bind(Reference(LineReader.UP_LINE_OR_HISTORY), "\u001b[A", "\u001bOA")
            mainKeyMap.bind(Reference(LineReader.DOWN_LINE_OR_HISTORY), "\u001b[B", "\u001bOB")

            // Arrow keys: Left/Right for character navigation
            mainKeyMap.bind(Reference(LineReader.BACKWARD_CHAR), "\u001b[D", "\u001bOD")
            mainKeyMap.bind(Reference(LineReader.FORWARD_CHAR), "\u001b[C", "\u001bOC")

            // Word navigation: Ctrl+Left / Alt+Left and Ctrl+Right / Alt+Right
            mainKeyMap.bind(Reference(LineReader.BACKWARD_WORD), "\u001b[1;5D", "\u001b[1;3D", "\u001b[5D", "\u001bb")
            mainKeyMap.bind(Reference(LineReader.FORWARD_WORD), "\u001b[1;5C", "\u001b[1;3C", "\u001b[5C", "\u001bf")

            // Typing '.' after a registered variable or bean immediately triggers completion listing
            reader.widgets["dot-complete"] = Widget {
                reader.buffer.write(".")
                val text = reader.buffer.toString()
                val cursor = reader.buffer.cursor()
                val textBefore = text.substring(0, cursor)
                val dotIndex = textBefore.lastIndexOf('.')
                if (dotIndex > 0) {
                    val receiver = textBefore.substring(0, dotIndex).trim()
                        .split(Regex("[^A-Za-z0-9_`?]")).lastOrNull()
                        ?.removePrefix("`")?.removeSuffix("`")?.removeSuffix("?")
                    if (!receiver.isNullOrEmpty() &&
                        (receiver in boundBeanNames() || receiver in variables || receiver == "context")
                    ) {
                        try {
                            reader.callWidget(LineReader.LIST_CHOICES)
                        } catch (e: Exception) {
                            try {
                                reader.callWidget(LineReader.EXPAND_OR_COMPLETE)
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
                true
            }
            mainKeyMap.bind(Reference("dot-complete"), ".")
        }

        return reader
    }

    /**
     * Reads continuation lines while brackets or string literals are
     * unbalanced, so a multi-line snippet can be typed naturally. This is a
     * *naive* continuation (bracket counting, not parsing), which is the same
     * trade-off REPLs such as `scala` make and is sufficient for interactive
     * use.
     *
     * End-of-input while a snippet is still open simply evaluates what was
     * typed; the compiler then reports the real syntax error.
     */
    private fun readCompleteSnippet(reader: LineReader, firstLine: String): String = buildString {
        append(firstLine)
        while (!bracketsBalanced(toString())) {
            val next = try {
                reader.readLine("  ... ")
            } catch (e: EndOfFileException) {
                return@buildString
            } catch (e: UserInterruptException) {
                return@buildString
            }
            append('\n').append(next)
        }
    }

    private fun evalOrWarn(output: ReplOutput, code: String): EvalResult? {
        val console = consoleSupplier()
        if (console == null) {
            output.error("Application context is not available (restarting?). Try again shortly.")
            return null
        }
        return console.eval(code)
    }

    /**
     * Records variable and function names declared by a successful snippet so the
     * completer can offer them at the start of the next line, and resolves their
     * types so members can be completed after '.'.
     */
    internal fun trackDeclarations(
        variables: MutableSet<String>,
        variableTypes: MutableMap<String, Class<*>>,
        input: String,
        result: EvalResult,
    ) {
        if (result.status != EvalStatus.SUCCESS) return

        val javaDecl = JavaDeclarationPreprocessor.parseDeclaration(input)
        if (javaDecl != null) {
            variables.add(javaDecl.name)
            val resolved = resolveJavaType(javaDecl.typeName) ?: resolveVariableType(javaDecl.name)
            if (resolved != null) {
                variableTypes[javaDecl.name] = resolved
            }
            return
        }

        DECLARATION.findAll(input).forEach { match ->
            val name = match.groupValues[1]
            variables.add(name)
            if (name !in variableTypes) {
                resolveVariableType(name)?.let { variableTypes[name] = it }
            }
        }
    }

    private fun resolveJavaType(typeName: String): Class<*>? {
        return when (typeName) {
            "String" -> String::class.java
            "Int", "int" -> java.lang.Integer::class.java
            "Long", "long" -> java.lang.Long::class.java
            "Boolean", "boolean" -> java.lang.Boolean::class.java
            "Double", "double" -> java.lang.Double::class.java
            "Float", "float" -> java.lang.Float::class.java
            "Byte", "byte" -> java.lang.Byte::class.java
            "Short", "short" -> java.lang.Short::class.java
            "Char", "char" -> java.lang.Character::class.java
            else -> {
                val console = consoleSupplier()
                val cl = console?.classLoader ?: Thread.currentThread().contextClassLoader
                try {
                    Class.forName(typeName, false, cl)
                } catch (e: Exception) {
                    try {
                        Class.forName("java.lang.$typeName", false, cl)
                    } catch (e2: Exception) {
                        try {
                            Class.forName("java.util.$typeName", false, cl)
                        } catch (e3: Exception) {
                            console?.resolveClassBySimpleName(typeName)
                        }
                    }
                }
            }
        }
    }

    private fun resolveVariableType(name: String): Class<*>? {
        val console = consoleSupplier() ?: return null
        val evalRes = try {
            console.eval("($name)?.javaClass?.name")
        } catch (e: Exception) {
            null
        } ?: return null

        if (evalRes.status != EvalStatus.SUCCESS) return null
        val typeName = evalRes.result?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        val cl = console.classLoader ?: Thread.currentThread().contextClassLoader
        return try {
            val loaded = Class.forName(typeName, false, cl)
            org.springframework.util.ClassUtils.getUserClass(loaded)
        } catch (e: Exception) {
            console.resolveClassBySimpleName(typeName.substringAfterLast('.'))
        }
    }

    private fun boundBeanNames(): List<String> =
        consoleSupplier()?.listBeans()?.flatMap { listOf(it.replName, it.name) }?.distinct() ?: emptyList()

    private fun prompt(terminal: Terminal): String {
        return AttributedString("spring-console> ", ReplTheme.prompt).toAnsi(terminal)
    }

    fun stop() {
        running = false
        thread?.interrupt()
        releaseTerminal()
        thread = null
    }

    /**
     * Waits up to [timeoutMs] for the REPL loop to finish (end of input, or
     * `:quit`). Returns true when the loop has stopped.
     *
     * Scripted terminals (tests, embedded use) need this to know when the
     * session is over; an interactive user simply keeps typing.
     */
    fun awaitTermination(timeoutMs: Long): Boolean {
        val worker = thread ?: return true
        worker.join(timeoutMs)
        return !worker.isAlive
    }

    override fun close() = stop()

    companion object {
        private val DECLARATION = Regex("""\b(?:val|var|fun)\s+([A-Za-z_][A-Za-z0-9_]*)""")

        /** `~/.spring-console-history`, or null when the home directory is unknown. */
        fun defaultHistoryFile(): String? =
            System.getProperty("user.home")?.let { "$it/.spring-console-history" }
    }
}

/**
 * Whether [code]'s brackets/quotes are balanced, i.e. the snippet is complete
 * and need not be continued on another line.
 *
 * Counts `()`, `[]` and `{}` outside string and character literals; unbalanced
 * *closing* brackets also terminate the input (the compiler will report the
 * error rather than the REPL silently waiting for more input).
 */
internal fun bracketsBalanced(code: String): Boolean {
    var round = 0
    var square = 0
    var curly = 0
    var inString = false
    var inChar = false
    var escaped = false
    for (c in code) {
        when {
            escaped -> escaped = false
            c == '\\' && (inString || inChar) -> escaped = true
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
    return round <= 0 && square <= 0 && curly <= 0 && !inString && !inChar
}

/**
 * Builds the terminal the REPL should use, preferring the process's **own
 * standard streams** whenever they are attached to a real terminal.
 *
 * ### Why not just `system(true)`?
 *
 * JLine's usual recipe —
 * `TerminalBuilder.builder().system(true).dumb(true).build()` — asks JLine's
 * native providers (JNI/JNA/Jansi/FFM/exec) to open the controlling terminal
 * (`/dev/tty`). Those providers can fail even when the process is attached to a
 * perfectly good terminal: that happens on macOS when the application runs from
 * a repackaged executable jar, and when it runs under Gradle's `bootRun` (the
 * build daemon has no controlling terminal). With `dumb(true)`, JLine then
 * silently returns a *dumb* terminal, and the REPL is skipped with
 * "No interactive terminal detected" even though the user is sitting at a
 * terminal — while stdin/stdout are in fact a TTY.
 *
 * So: when [isStdioTerminal] says the standard streams are a terminal, build the
 * terminal over them with an explicit `TERM` type. Otherwise fall back to the
 * native system terminal, and finally to a dumb terminal (which keeps CI,
 * daemons, and stdio-MCP mode working — the REPL just does not start there).
 */
private fun defaultConsoleTerminal(): Terminal {
    val console = System.console()
    return if (isStdioTerminal(console)) {
        try {
            TerminalBuilder.builder()
                .system(true)
                .build()
                .also { ensureSensibleTerminalSize(it) }
        } catch (e: Exception) {
            try {
                TerminalBuilder.builder()
                    .system(false)
                    .streams(System.`in`, System.out)
                    .type(terminalType(System.getenv("TERM")))
                    .build()
                    .also { ensureSensibleTerminalSize(it) }
            } catch (e2: Exception) {
                TerminalBuilder.builder().system(true).dumb(true).build()
            }
        }
    } else {
        TerminalBuilder.builder().system(true).dumb(true).build()
    }
}

private fun ensureSensibleTerminalSize(terminal: Terminal) {
    if (terminal.width <= 0 || terminal.height <= 0) {
        terminal.size = org.jline.terminal.Size(80, 24)
    }
}

/**
 * Whether the JVM's stdin/stdout are an interactive terminal.
 *
 * [console] is `System.console()`, passed in (and typed as `Any?`) so both
 * branches are unit-testable without a real terminal:
 *
 * - `null` → no terminal attached (piped input, CI, a Gradle daemon).
 * - On JDK 22+, `System.console()` is non-null even when input is redirected,
 *   so the newer `Console.isTerminal()` is consulted reflectively.
 * - On JDK 21 and earlier a non-null `Console` already proves a terminal, and
 *   `isTerminal()` does not exist.
 */
internal fun isStdioTerminal(console: Any?): Boolean {
    if (console == null) return false
    return try {
        console.javaClass.getMethod("isTerminal").invoke(console) as? Boolean ?: true
    } catch (e: NoSuchMethodException) {
        true // JDK <= 21: a non-null Console already means stdin/stdout are a TTY
    } catch (e: Exception) {
        true
    }
}

/**
 * Terminal type string for [term] (the `TERM` environment variable). Falls back
 * to `xterm-256color` when `TERM` is unset or `dumb`: by the time this is
 * called, [isStdioTerminal] has already established that a terminal exists, and
 * `dumb` would only disable colors and cursor handling.
 */
internal fun terminalType(term: String?): String =
    if (!term.isNullOrBlank() && term != "dumb") term else "xterm-256color"

