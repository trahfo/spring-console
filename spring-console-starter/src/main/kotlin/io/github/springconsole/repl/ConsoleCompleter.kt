package io.github.springconsole.repl

import io.github.springconsole.api.BeanDetails
import org.jline.reader.Candidate
import org.jline.reader.Completer
import org.jline.reader.LineReader
import org.jline.reader.ParsedLine
import java.lang.reflect.Modifier

/**
 * Tab completion for the Spring Console REPL, implemented as a JLine3
 * [Completer] — the established extension point JLine calls when the user
 * presses `<Tab>`.
 *
 * ### What gets completed
 *
 * | Input                | Offered completions                                             |
 * |----------------------|-----------------------------------------------------------------|
 * | `:he<Tab>`           | every `:command` whose name starts with `:he`, with help text   |
 * | `:inspect <Tab>`     | bound bean names and REPL names                                  |
 * | `todo<Tab>`          | bean REPL names, session `val`/`fun` names, and Kotlin keywords |
 * | `todoService.<Tab>`  | that bean's methods and properties, typed from introspection     |
 * | `context.<Tab>`      | `ApplicationContext` methods and properties                      |
 *
 * ### Design constraints
 *
 * - **No side effects.** Completion must never evaluate user code. Member
 *   completion is answered purely from [BeanDetails], which the console already
 *   computed from reflection (see `BeanIntrospector`); the `<Tab>` key can
 *   therefore never mutate the database or trigger lazy loading.
 * - **Never throws.** A completion callback runs inside JLine's event loop; an
 *   escaping exception would break the user's terminal. Everything is wrapped
 *   in a guard that degrades to "no candidates".
 * - **Testable without a terminal.** All inputs are supplied through function
 *   parameters and plain-data results, so the tests drive it with a small fake
 *   [ParsedLine].
 *
 * @param beanNames       supplies bound bean names (REPL name and Spring name).
 * @param inspectBean     resolves a bean's introspected shape, or null.
 * @param sessionVariables supplies `val`/`var`/`fun` names declared earlier in the session.
 * @param commands        the command surface; defaults to [REPL_COMMANDS].
 * @param keywords        Kotlin keywords offered at statement start.
 */
class ConsoleCompleter(
    private val beanNames: () -> List<String> = { emptyList() },
    private val inspectBean: (String) -> BeanDetails? = { null },
    private val sessionVariables: () -> Set<String> = { emptySet() },
    private val inspectVariable: (String) -> BeanDetails? = { null },
    private val commands: List<ReplCommandSpec> = REPL_COMMANDS,
    private val keywords: Set<String> = DEFAULT_KEYWORDS,
) : Completer {

    override fun complete(reader: LineReader?, line: ParsedLine, candidates: MutableList<Candidate>) {
        try {
            completeSafely(line, candidates)
        } catch (e: Exception) {
            // Editing must survive a buggy completion provider: report nothing.
        } catch (e: LinkageError) {
            // Optional integrations (e.g. missing Spring Data) must not break Tab.
        }
    }

    private fun completeSafely(line: ParsedLine, candidates: MutableList<Candidate>) {
        val buffer = line.line() ?: return
        val cursor = line.cursor()
        if (cursor < 0 || cursor > buffer.length) return
        val beforeCursor = buffer.substring(0, cursor)

        if (beforeCursor.trimStart().startsWith(":")) {
            completeCommand(beforeCursor.trimStart(), candidates)
            return
        }

        val word = line.word() ?: ""
        val wordCursor = line.wordCursor()
        if (wordCursor < 0) {
            // The cursor sits between words: offer every root symbol.
            completeRoot("", candidates)
            return
        }
        val wordBeforeCursor = word.substring(0, wordCursor.coerceIn(0, word.length))
        val dot = lastDotOutsideQuotes(wordBeforeCursor)
        if (dot >= 0) {
            val receiverExpr = wordBeforeCursor.substring(0, dot)
            val receiver = cleanReceiverName(receiverExpr)
            val receiverPrefix = wordBeforeCursor.substring(0, dot + 1)
            val prefix = wordBeforeCursor.substring(dot + 1)
            completeMembers(receiver, receiverPrefix, prefix, candidates)
        } else {
            completeRoot(wordBeforeCursor, candidates)
        }
    }

    private fun cleanReceiverName(receiverExpr: String): String {
        val trimmed = receiverExpr.trim().removeSuffix("?").trim()
        val tokenStart = trimmed.indexOfLast { !it.isJavaIdentifierPart() && it != '`' }
        val raw = if (tokenStart >= 0) trimmed.substring(tokenStart + 1) else trimmed
        return stripBackticks(raw)
    }

    // ------------------------------------------------------------------
    // Root symbols
    // ------------------------------------------------------------------

    private fun completeRoot(prefix: String, candidates: MutableList<Candidate>) {
        val seen = HashSet<String>()
        val beans = safeBeanNames()

        beans.forEach { name ->
            if (name.startsWith(prefix) && seen.add(name)) {
                add(candidates, name, group = "bean", descr = "bound bean")
            }
        }
        (sessionVariables() + "context").sorted().forEach { name ->
            if (name.startsWith(prefix) && seen.add(name)) {
                add(candidates, name, group = "variable", descr = "REPL value")
            }
        }
        if (prefix.isNotEmpty()) {
            keywords.sorted()
                .filter { it.startsWith(prefix) && it != prefix }
                .forEach { keyword ->
                    if (seen.add(keyword)) add(candidates, keyword, group = "keyword", descr = "Kotlin keyword")
                }
        }
    }

    // ------------------------------------------------------------------
    // Member access: receiver.member
    // ------------------------------------------------------------------

    private fun completeMembers(
        receiver: String,
        receiverPrefix: String,
        prefix: String,
        candidates: MutableList<Candidate>,
    ) {
        if (receiver.isEmpty()) return
        if (receiver == "context") {
            contextCandidates(receiverPrefix).forEach {
                if (it.value().startsWith(receiverPrefix + prefix, ignoreCase = true)) candidates.add(it)
            }
            return
        }
        val details = safeInspect(receiver) ?: return
        val seen = HashSet<String>()

        details.properties
            .filter { it.readable && it.name.startsWith(prefix, ignoreCase = true) }
            .sortedBy { it.name }
            .forEach { property ->
                val memberName = property.name
                val fullValue = "$receiverPrefix$memberName"
                if (seen.add(fullValue)) {
                    add(
                        candidates,
                        value = fullValue,
                        group = "property",
                        displ = memberName,
                        descr = property.type,
                    )
                }
            }

        details.methods
            .filter {
                it.name.startsWith(prefix, ignoreCase = true) &&
                    it.name !in ReplReflection.OBJECT_METHODS &&
                    !it.name.contains('$')
            }
            .sortedWith(compareBy({ it.name }, { it.parameters.size }))
            .forEach { method ->
                val hasParameters = method.parameters.isNotEmpty()
                val memberValue = if (hasParameters) "${method.name}(" else "${method.name}()"
                val fullValue = "$receiverPrefix$memberValue"
                if (!seen.add(fullValue)) return@forEach
                val signature = method.parameters.joinToString(", ") { "${it.name}: ${it.type}" }
                add(
                    candidates,
                    value = fullValue,
                    group = "method",
                    displ = if (hasParameters) "${method.name}($signature)" else memberValue,
                    descr = method.returnType,
                    suffix = if (hasParameters) ")" else "",
                    complete = !hasParameters,
                )
            }
    }

    /**
     * Completion candidates for the `context` binding.
     *
     * Derived from `ApplicationContext`'s **reflected** methods rather than
     * from `java.beans.Introspector`: the introspector only recognizes
     * properties declared on classes, so on an interface it would silently
     * return none. `getEnvironment()` therefore shows up as the `environment`
     * property, while parameterized methods such as `getBean(` are offered as
     * call-shaped candidates with a closing-paren suffix.
     */
    internal fun contextCandidates(receiverPrefix: String = "context."): List<Candidate> {
        val type = org.springframework.context.ApplicationContext::class.java
        val out = LinkedHashMap<String, Candidate>()

        type.methods
            .filter {
                Modifier.isPublic(it.modifiers) &&
                    !it.isSynthetic &&
                    it.declaringClass != Any::class.java &&
                    !it.name.contains('$')
            }
            .sortedWith(compareBy({ it.name }, { it.parameterCount }))
            .forEach { method ->
                if (method.name in ReplReflection.OBJECT_METHODS) return@forEach
                when {
                    ReplReflection.isGetter(method) -> {
                        val name = ReplReflection.propertyName(method)
                        val fullValue = "$receiverPrefix$name"
                        out.putIfAbsent(
                            fullValue,
                            Candidate(fullValue, name, "context-property", method.returnType.simpleName, "", null, true),
                        )
                    }
                    method.parameterCount > 0 -> {
                        val memberValue = "${method.name}("
                        val fullValue = "$receiverPrefix$memberValue"
                        val signature = method.parameterTypes.joinToString(", ") { it.simpleName }
                        out.putIfAbsent(
                            fullValue,
                            Candidate(fullValue, "${method.name}($signature)", "context-method", method.returnType.simpleName, ")", null, false),
                        )
                    }
                }
            }
        return out.values.toList()
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    private fun completeCommand(input: String, candidates: MutableList<Candidate>) {
        val parts = input.split(Regex("\\s+"))
        if (parts.size <= 1) {
            val prefix = parts.firstOrNull().orEmpty()
            commands.forEach { command ->
                if (command.name.startsWith(prefix)) {
                    add(
                        candidates,
                        value = command.name,
                        group = "command",
                        displ = command.usage,
                        descr = command.description,
                        complete = command.arguments.isBlank(),
                    )
                }
            }
            return
        }

        val command = commands.firstOrNull { it.name == parts[0] } ?: return
        val prefix = parts.last()
        when (command.argumentKind) {
            ReplCommandSpec.ArgumentKind.BEAN -> {
                safeBeanNames().sorted().forEach { name ->
                    if (name.startsWith(prefix)) add(candidates, name, group = "bean", descr = "bean name")
                }
            }
            ReplCommandSpec.ArgumentKind.LITERAL -> {
                REPL_LITERAL_ARGUMENTS[command.name].orEmpty().forEach { literal ->
                    if (literal.startsWith(prefix)) add(candidates, literal, group = "argument", descr = command.name)
                }
            }
            ReplCommandSpec.ArgumentKind.NONE -> Unit
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun add(
        candidates: MutableList<Candidate>,
        value: String,
        group: String,
        displ: String = value,
        descr: String? = null,
        suffix: String = "",
        complete: Boolean = true,
    ) {
        candidates.add(Candidate(value, displ, group, descr, suffix, null, complete))
    }

    private fun safeBeanNames(): List<String> = try {
        beanNames().filter { it.isNotBlank() }.distinct()
    } catch (e: Exception) {
        emptyList()
    }

    private fun safeInspect(name: String): BeanDetails? = try {
        inspectBean(name) ?: inspectVariable(name)
    } catch (e: Exception) {
        null
    }

    /** Finds the last `.` before the cursor that is not inside a string literal. */
    private fun lastDotOutsideQuotes(text: String): Int {
        var inString = false
        var inChar = false
        var escaped = false
        var lastDot = -1
        for ((index, c) in text.withIndex()) {
            when {
                escaped -> escaped = false
                c == '\\' && (inString || inChar) -> escaped = true
                inString -> if (c == '"') inString = false
                inChar -> if (c == '\'') inChar = false
                c == '"' -> inString = true
                c == '\'' -> inChar = true
                c == '.' -> lastDot = index
            }
        }
        return lastDot
    }

    private fun stripBackticks(name: String): String =
        if (name.length >= 2 && name.startsWith("`") && name.endsWith("`")) name.substring(1, name.length - 1) else name

    private companion object {
        /**
         * Keywords offered at statement start: all hard keywords plus the soft
         * keywords that can legitimately start a declaration, minus the
         * contextual ones (`it`, `field`, `get`, …) that are noise at the
         * beginning of a line.
         */
        val DEFAULT_KEYWORDS: Set<String> = KotlinLexer.HARD_KEYWORDS +
            (KotlinLexer.SOFT_KEYWORDS - setOf(
                "it", "value", "field", "get", "set", "param", "receiver", "property",
                "file", "delegate", "dynamic", "setparam", "by", "catch", "finally",
                "where", "init", "constructor", "companion",
            ))
    }
}
