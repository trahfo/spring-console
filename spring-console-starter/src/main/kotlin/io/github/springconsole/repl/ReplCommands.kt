package io.github.springconsole.repl

/**
 * Metadata for one `:command` understood by the terminal REPL.
 *
 * Keeping the commands in a data structure (instead of a `when` over string
 * literals) means the command handler, the help text, and the tab-completer all
 * read from the *same* list and can never drift apart.
 *
 * @param name           the command including its leading colon, e.g. `:beans`.
 * @param arguments      human-readable argument placeholder, e.g. `[package]`.
 * @param description    one-line help text.
 * @param argumentKind   how tab completion should treat the command's argument.
 */
data class ReplCommandSpec(
    val name: String,
    val arguments: String = "",
    val description: String = "",
    val argumentKind: ArgumentKind = ArgumentKind.NONE,
) {
    /** Full usage form, e.g. `:inspect <beanName>`. */
    val usage: String get() = if (arguments.isBlank()) name else "$name $arguments"

    /** Completion strategy for a command's first argument. */
    enum class ArgumentKind {
        NONE,
        /** Complete bean names / REPL names. */
        BEAN,
        /** Complete from a fixed set of literals (e.g. `on`/`off`). */
        LITERAL,
    }
}

/**
 * The complete command surface of the REPL. This is the single source of truth
 * used by [ReplCommandHandler] (to execute and to print `:help`) and by
 * [ConsoleCompleter] (to offer completions).
 */
val REPL_COMMANDS: List<ReplCommandSpec> = listOf(
    ReplCommandSpec(":help", description = "show this help"),
    ReplCommandSpec(":h", description = "alias for :help"),
    ReplCommandSpec(":beans", "[package]", "list beans bound into the REPL scope", ReplCommandSpec.ArgumentKind.BEAN),
    ReplCommandSpec(":inspect", "<beanName>", "show a bean's methods and types", ReplCommandSpec.ArgumentKind.BEAN),
    ReplCommandSpec(":schema", description = "dump entities, repositories, and services"),
    ReplCommandSpec(":reload", "[--no-compile]", "recompile sources and hot-restart the context", ReplCommandSpec.ArgumentKind.LITERAL),
    ReplCommandSpec(":quit", description = "detach the console (stops the app if it has no other interface)"),
    ReplCommandSpec(":q", description = "alias for :quit"),
    ReplCommandSpec(":exit", description = "alias for :quit"),
)

/** Fixed argument completions for commands declared with [ReplCommandSpec.ArgumentKind.LITERAL]. */
val REPL_LITERAL_ARGUMENTS: Map<String, List<String>> = mapOf(
    ":reload" to listOf("--no-compile"),
)
