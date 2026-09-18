package io.github.springconsole.reload

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuildErrorParserTest {

    @Test
    fun `parses kotlinc gradle errors with file uri`() {
        val output = """
            > Task :app:compileKotlin FAILED
            e: file:///Users/dev/project/src/main/kotlin/com/example/TodoService.kt:42:17 Unresolved reference: findByEmail
            e: file:///Users/dev/project/src/main/kotlin/com/example/Other.kt:7:1 Expecting a top level declaration
        """.trimIndent()

        val errors = BuildErrorParser.parse(output)

        assertEquals(2, errors.size)
        assertEquals(42, errors[0].line)
        assertEquals(17, errors[0].column)
        assertEquals("TodoService.kt: Unresolved reference: findByEmail", errors[0].message)
    }

    @Test
    fun `parses legacy kotlinc errors`() {
        val output = "e: /project/src/main/kotlin/Foo.kt: (12, 34): Type mismatch: inferred type is String but Int was expected"
        val errors = BuildErrorParser.parse(output)
        assertEquals(1, errors.size)
        assertEquals(12, errors[0].line)
        assertEquals(34, errors[0].column)
        assertTrue(errors[0].message.startsWith("Foo.kt: Type mismatch"))
    }

    @Test
    fun `parses javac errors with caret column`() {
        val output = """
            /project/src/main/java/com/example/Todo.java:23: error: cannot find symbol
                    return titel;
                           ^
              symbol:   variable titel
        """.trimIndent()

        val errors = BuildErrorParser.parse(output)

        assertEquals(1, errors.size)
        assertEquals(23, errors[0].line)
        // javac's caret is aligned to the echoed source line; here it points at "titel"
        assertEquals(16, errors[0].column)
        assertEquals("Todo.java: cannot find symbol", errors[0].message)
    }

    @Test
    fun `parses maven javac errors`() {
        val output = "[ERROR] /project/src/main/java/App.java:[5,8] class App is public, should be declared in a file named App.java"
        val errors = BuildErrorParser.parse(output)
        assertEquals(1, errors.size)
        assertEquals(5, errors[0].line)
        assertEquals(8, errors[0].column)
    }

    @Test
    fun `ignores unrelated output and deduplicates`() {
        val output = """
            > Task :compileJava
            Note: some notes
            e: file:///x/A.kt:1:1 broken
            e: file:///x/A.kt:1:1 broken
            BUILD FAILED in 2s
        """.trimIndent()
        assertEquals(1, BuildErrorParser.parse(output).size)
    }

    @Test
    fun `returns empty list for successful build output`() {
        assertTrue(BuildErrorParser.parse("BUILD SUCCESSFUL in 1s\n2 actionable tasks").isEmpty())
    }
}
