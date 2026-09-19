package io.github.springconsole.repl

import io.github.springconsole.ConsoleOperations
import io.github.springconsole.api.BeanSummary
import io.github.springconsole.api.ReloadResult
import io.github.springconsole.api.ReloadStatus
import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder

/**
 * Executes the `:commands` of the REPL and owns the sandbox toggle state.
 *
 * Extracted from the read-eval-print loop so that the command surface is
 * testable without a pseudo-terminal: tests construct a [ReplOutput] over an
 * in-memory JLine terminal, call [handle], and assert on the emitted text.
 *
 * The handler never throws: a failing command is reported as a red error line
 * and the session continues. [handle] returns `false` only for the detach
 * commands (`:quit`, `:q`, `:exit`).
 */
class ReplCommandHandler(
    private val output: ReplOutput,
    private val consoleSupplier: () -> ConsoleOperations?,
    private val reloadHandler: ((recompile: Boolean) -> ReloadResult)? = null,
) {

    /** @return false when the REPL should exit. */
    fun handle(input: String): Boolean {
        val parts = input.split(Regex("\\s+"), limit = 2)
        val argument = parts.getOrNull(1)?.trim()
        return try {
            dispatch(parts[0], argument)
        } catch (e: Exception) {
            output.error("${e.javaClass.simpleName}: ${e.message}")
            true
        }
    }

    private fun dispatch(command: String, argument: String?): Boolean = when (command) {
        ":help", ":h" -> true.also { printHelp() }
        ":quit", ":q", ":exit" -> {
            output.info("Console detached.")
            false
        }
        ":beans" -> true.also { listBeans(argument) }
        ":inspect" -> true.also { inspect(argument) }
        ":schema" -> true.also { schema() }
        ":reload" -> true.also { reload(argument) }
        else -> true.also { output.warning("Unknown command $command (try :help)") }
    }

    private fun listBeans(packageFilter: String?) {
        val console = console ?: return
        val filter = packageFilter?.takeIf { it.isNotBlank() }
        val beans = console.listBeans(filter)
        output.lines(beans.map(::formatBean))
        output.info("${beans.size} beans bound. Use them by name in snippets.")
    }

    private fun inspect(beanName: String?) {
        if (beanName.isNullOrBlank()) {
            output.info("Usage: :inspect <beanName>")
            return
        }
        val console = console ?: return
        val details = console.inspectBean(beanName)
        if (details == null) {
            output.error("No bean named '$beanName' (try :beans)")
            return
        }
        val header = AttributedStringBuilder()
        header.styled(ReplTheme.typeName, details.targetType)
        header.styled(ReplTheme.dim, " (bean '${details.name}', repl name '${details.replName}'")
        if (details.proxied) header.styled(ReplTheme.dim, ", proxied")
        header.styled(ReplTheme.dim, ")")
        val lines = mutableListOf(header.toAttributedString())
        details.methods.forEach { method ->
            val builder = AttributedStringBuilder()
            val parameters = method.parameters.joinToString(", ") { "${it.name}: ${it.type}" }
            builder.styled(ReplTheme.dim, "  .")
            builder.styled(ReplTheme.key, method.name)
            builder.styled(ReplTheme.identifier, "(")
            builder.styled(ReplTheme.typeName, parameters)
            builder.styled(ReplTheme.identifier, "): ")
            builder.styled(ReplTheme.typeName, method.returnType)
            lines += builder.toAttributedString()
        }
        output.lines(lines)
    }

    private fun schema() {
        val console = console ?: return
        val schema = console.contextSchema()
        val lines = mutableListOf<AttributedString>()
        schema.entities.forEach { entity ->
            lines += AttributedStringBuilder()
                .styled(ReplTheme.dim, "  entity ")
                .styled(ReplTheme.typeName, entity.name)
                .styled(ReplTheme.dim, " (${entity.type})")
                .toAttributedString()
            entity.attributes.forEach { attribute ->
                lines += AttributedStringBuilder()
                    .styled(ReplTheme.key, "    ${attribute.name}")
                    .styled(ReplTheme.dim, ": ${attribute.type}")
                    .apply { if (attribute.id) styled(ReplTheme.warning, " [id]") }
                    .toAttributedString()
            }
        }
        schema.repositories.forEach { repository ->
            lines += AttributedStringBuilder()
                .styled(ReplTheme.dim, "  repository ")
                .styled(ReplTheme.key, repository.replName)
                .styled(ReplTheme.dim, ": ${repository.type} -> ${repository.domainType ?: "?"}")
                .toAttributedString()
        }
        schema.services.forEach { service ->
            lines += AttributedStringBuilder()
                .styled(ReplTheme.dim, "  service ")
                .styled(ReplTheme.key, service.replName)
                .styled(ReplTheme.dim, ": ${service.type}")
                .toAttributedString()
        }
        output.lines(lines)
    }

    private fun reload(argument: String?) {
        val handler = reloadHandler
        if (handler == null) {
            output.error("Reload is not available in this runtime.")
            return
        }
        output.info("Recompiling and restarting context…")
        val result = handler(argument != "--no-compile")
        when (result.status) {
            ReloadStatus.SUCCESS -> output.success(result.message)
            else -> {
                output.error(result.message)
                result.compilationErrors?.forEach { error ->
                    output.error("  ${error.line}:${error.column} ${error.message}")
                }
            }
        }
    }

    /** The banner printed once when the REPL starts. */
    fun printBanner() {
        output.success("Spring Console — Kotlin REPL bound to this application context")
        output.info("Kotlin code is syntax-highlighted; press Tab to complete beans, members, and commands.")
        output.info("Type :help for commands.")
    }

    /** Renders the help table from [REPL_COMMANDS] so it can never drift. */
    fun printHelp() {
        val lines = mutableListOf<AttributedString>()
        lines += AttributedString("Spring Console commands:", ReplTheme.header)
        val width = REPL_COMMANDS.maxOf { it.usage.length }
        REPL_COMMANDS.forEach { command ->
            lines += AttributedStringBuilder()
                .styled(ReplTheme.key, "  " + command.usage.padEnd(width))
                .styled(ReplTheme.identifier, "  " + command.description)
                .toAttributedString()
        }
        lines += AttributedString("", ReplTheme.identifier)
        lines += AttributedString("Anything else is evaluated as Kotlin against the live context, e.g.:", ReplTheme.info)
        lines += AttributedString("  context.beanDefinitionNames.take(5)", ReplTheme.identifier)
        lines += AttributedString("  myService.findAll()", ReplTheme.identifier)
        lines += AttributedString("  val items = myService.findAll()", ReplTheme.identifier)
        lines += AttributedString("  items              # show the value again as a table", ReplTheme.identifier)
        output.lines(lines)
    }

    private fun formatBean(bean: BeanSummary): AttributedString =
        AttributedStringBuilder()
            .styled(ReplTheme.key, "  " + bean.replName.padEnd(40))
            .styled(ReplTheme.typeName, bean.type)
            .apply { if (bean.proxied) styled(ReplTheme.dim, " (proxied)") }
            .toAttributedString()

    private val console: ConsoleOperations?
        get() = consoleSupplier().also { if (it == null) output.error("Application context is not available (restarting?).") }
}
