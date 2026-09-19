package io.github.springconsole.repl

import io.github.springconsole.api.BeanDetails
import io.github.springconsole.api.MethodSignature
import io.github.springconsole.api.ParameterInfo
import io.github.springconsole.api.PropertyInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for tab completion. The completer is driven through a fake
 * [org.jline.reader.ParsedLine] (see [FakeParsedLine]) with a `null` reader, so
 * the tests exercise exactly the metadata-driven logic that production uses —
 * no terminal, no evaluation, no side effects.
 */
class ConsoleCompleterTest {

    private val todoService = BeanDetails(
        name = "todoService",
        replName = "todoService",
        targetType = "com.example.todo.TodoService",
        runtimeType = "com.example.todo.TodoService\$\$SpringCGLIB\$\$0",
        proxied = true,
        scope = "singleton",
        interfaces = emptyList(),
        methods = listOf(
            MethodSignature("findAll", emptyList(), "List<Todo>", "TodoService"),
            MethodSignature("findById", listOf(ParameterInfo("id", "Long")), "Todo", "TodoService"),
            // overload: must not duplicate the candidate
            MethodSignature("findById", listOf(ParameterInfo("id", "Long"), ParameterInfo("fresh", "Boolean")), "Todo", "TodoService"),
            MethodSignature("create", listOf(ParameterInfo("request", "TodoRequest")), "Todo", "TodoService"),
            MethodSignature("getClass", emptyList(), "Class<*>", "java.lang.Object"),
        ),
        properties = listOf(
            PropertyInfo("repository", "TodoRepository", readable = true, writable = false),
            PropertyInfo("writeOnly", "String", readable = false, writable = true),
        ),
    )

    private val completer = ConsoleCompleter(
        beanNames = { listOf("todoService", "todoRepository", "context") },
        inspectBean = { name -> if (name == "todoService") todoService else null },
        sessionVariables = { setOf("overdue", "findings") },
    )

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    @Test
    fun `completes command names with usage and description`() {
        val candidates = complete(completer, ":he")
        val help = candidates.firstOrNull { it.value() == ":help" }

        assertTrue(help != null, "expected :help in ${candidates.map { it.value() }}")
        assertEquals(":help", help!!.displ())
        assertEquals("command", help.group())
        assertEquals("show this help", help.descr())
    }

    @Test
    fun `completes every command for a bare colon`() {
        val values = completedValues(completer, ":")
        assertTrue(values.containsAll(listOf(":help", ":beans", ":inspect", ":schema", ":reload", ":quit")))
    }

    @Test
    fun `completes bean names as command arguments`() {
        val values = completedValues(completer, ":inspect to")
        assertTrue(values.contains("todoService"), "got: $values")
        assertTrue(values.contains("todoRepository"), "got: $values")
        assertFalse(values.contains("overdue"))
    }

    @Test
    fun `completes bean names for an empty inspect argument`() {
        val values = completedValues(completer, ":inspect ")
        assertTrue(values.containsAll(listOf("todoService", "todoRepository", "context")), "got: $values")
    }

    @Test
    fun `completes literal command arguments`() {
        assertEquals(listOf("--no-compile"), completedValues(completer, ":reload --"))
    }

    @Test
    fun `context exposes ApplicationContext members`() {
        val values = completer.contextCandidates().map { it.value() }
        assertTrue(values.any { it.contains("getBean") }, "values=$values")
        assertTrue(values.contains("context.environment"), "values=$values")
    }

    // ------------------------------------------------------------------
    // Root symbols
    // ------------------------------------------------------------------

    @Test
    fun `completes bean names and session variables at statement start`() {
        val values = completedValues(completer, "todo")
        assertTrue(values.containsAll(listOf("todoService", "todoRepository")), "got: $values")
    }

    @Test
    fun `completes session variables and Kotlin keywords`() {
        val values = completedValues(completer, "f")
        assertTrue(values.contains("findings"), "got: $values")
        assertTrue(values.containsAll(listOf("false", "for", "fun")), "got: $values")

        // soft keywords that can start a declaration are offered too
        assertTrue(completedValues(completer, "sus").contains("suspend"))
    }

    @Test
    fun `does not offer keywords for an empty prefix`() {
        val values = completedValues(completer, "")
        assertFalse(values.contains("finally"), "keyword flooding: $values")
        assertTrue(values.contains("todoService"))
    }

    @Test
    fun `completes the context binding`() {
        assertTrue(completedValues(completer, "con").contains("context"))
    }

    // ------------------------------------------------------------------
    // Member access
    // ------------------------------------------------------------------

    @Test
    fun `completes methods and properties after a dot`() {
        val values = completedValues(completer, "todoService.")
        assertTrue(values.contains("todoService.findAll()"), "got: $values")
        assertTrue(values.contains("todoService.findById("), "got: $values")
        assertTrue(values.contains("todoService.repository"), "got: $values")

        val displays = completedDisplays(completer, "todoService.")
        assertTrue(displays.contains("findAll()"), "got displays: $displays")
        assertTrue(displays.contains("repository"), "got displays: $displays")
    }

    @Test
    fun `filters members by the typed prefix`() {
        val values = completedValues(completer, "todoService.fin")
        assertEquals(setOf("todoService.findAll()", "todoService.findById("), values.toSet())
    }

    @Test
    fun `method candidates carry their signature and a closing-paren suffix`() {
        val findById = complete(completer, "todoService.findById").first { it.value() == "todoService.findById(" }
        assertTrue(findById.suffix() == ")", "suffix: ${findById.suffix()}")
        assertFalse(findById.complete(), "methods with parameters must not be treated as complete")
        assertTrue(findById.displ().startsWith("findById("), "displ: ${findById.displ()}")
        assertEquals("Todo", findById.descr())

        val findAll = complete(completer, "todoService.findAll").first { it.value() == "todoService.findAll()" }
        assertTrue(findAll.complete(), "no-arg methods are complete words")
        assertEquals("", findAll.suffix())
    }

    @Test
    fun `overloads are offered once and Object methods are hidden`() {
        val values = completedValues(completer, "todoService.")
        assertEquals(1, values.count { it == "todoService.findById(" }, "got: $values")
        assertFalse(values.any { it.contains("getClass") }, "got: $values")
    }

    @Test
    fun `non readable properties are not offered`() {
        val values = completedValues(completer, "todoService.")
        assertFalse(values.contains("todoService.writeOnly"), "got: $values")
    }

    @Test
    fun `completes ApplicationContext members for the context binding`() {
        val methods = completedValues(completer, "context.getB")
        assertTrue(methods.contains("context.getBean("), "got: $methods")

        val properties = completedValues(completer, "context.env")
        assertTrue(properties.contains("context.environment"), "got: $properties")
    }

    @Test
    fun `does not complete members of an unknown receiver`() {
        assertEquals(emptyList(), completedValues(completer, "nope.anything"))
    }

    @Test
    fun `backticked receivers are resolved`() {
        val values = completedValues(completer, "`todoService`.fin")
        assertTrue(values.contains("`todoService`.findById("), "got: $values")
    }

    @Test
    fun `safe-call receiver is resolved`() {
        assertTrue(completedValues(completer, "todoService?.fin").contains("todoService?.findById("))
    }

    @Test
    fun `a dot inside a string is not treated as member access`() {
        // The receiver would be `"a` — not a bean, so no completions and no crash.
        assertEquals(emptyList(), completedValues(completer, "\"a.b"))
    }

    @Test
    fun `completion never throws on pathological input`() {
        listOf("", "@@@", "..", "``", "((((", ":", ":unknown x", "todoService.", "\u0000").forEach { input ->
            complete(completer, input)
        }
    }

    @Test
    fun `a failing bean registry degrades to no completions`() {
        val broken = ConsoleCompleter(
            beanNames = { throw IllegalStateException("boom") },
            inspectBean = { throw IllegalStateException("boom") },
        )
        assertEquals(emptyList(), completedValues(broken, "todo"))
        assertEquals(emptyList(), completedValues(broken, "todoService.fin"))
    }

    @Test
    fun `cursor in the middle of a word completes only the prefix before it`() {
        val text = "todoService.finAll"
        val values = completedValues(completer, text, cursor = "todoService.fin".length)
        assertEquals(setOf("todoService.findAll()", "todoService.findById("), values.toSet())
    }

    @Test
    fun `completes methods on session variables via inspectVariable`() {
        val stringDetails = BeanDetails(
            name = "x",
            replName = "x",
            targetType = "java.lang.String",
            runtimeType = "java.lang.String",
            proxied = false,
            scope = "session",
            interfaces = emptyList(),
            methods = listOf(
                MethodSignature("length", emptyList(), "Int", "String"),
                MethodSignature("substring", listOf(ParameterInfo("beginIndex", "Int")), "String", "String"),
                MethodSignature("toLowerCase", emptyList(), "String", "String"),
            ),
            properties = emptyList(),
        )
        val varCompleter = ConsoleCompleter(
            beanNames = { listOf("todoService") },
            inspectBean = { name -> if (name == "todoService") todoService else null },
            sessionVariables = { setOf("x") },
            inspectVariable = { name -> if (name == "x") stringDetails else null },
        )

        // root completion includes session variable x
        assertTrue(completedValues(varCompleter, "x").contains("x"))

        // member completion on x shows its methods
        val methods = completedValues(varCompleter, "x.")
        assertTrue(methods.contains("x.length()"), "got: $methods")
        assertTrue(methods.contains("x.substring("), "got: $methods")
        assertTrue(methods.contains("x.toLowerCase()"), "got: $methods")

        val displays = completedDisplays(varCompleter, "x.")
        assertTrue(displays.contains("length()"), "got displays: $displays")

        // typing a letter filters the methods to that letter
        val sub = completedValues(varCompleter, "x.sub")
        assertEquals(listOf("x.substring("), sub)

        val to = completedValues(varCompleter, "x.to")
        assertEquals(listOf("x.toLowerCase()"), to)
    }
}
