package io.github.springconsole.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JavaDeclarationPreprocessorTest {

    @Test
    fun `translates uninitialized reference variable`() {
        val result = JavaDeclarationPreprocessor.preprocess("private String x;")
        assertEquals("var x: String? = null", result)

        val unadorned = JavaDeclarationPreprocessor.preprocess("String x;")
        assertEquals("var x: String? = null", unadorned)

        val withoutSemicolon = JavaDeclarationPreprocessor.preprocess("private String x")
        assertEquals("var x: String? = null", withoutSemicolon)
    }

    @Test
    fun `translates uninitialized primitive variables with sensible defaults`() {
        assertEquals("var count: Int = 0", JavaDeclarationPreprocessor.preprocess("private int count;"))
        assertEquals("var flag: Boolean = false", JavaDeclarationPreprocessor.preprocess("boolean flag;"))
        assertEquals("var amount: Double = 0.0", JavaDeclarationPreprocessor.preprocess("double amount;"))
        assertEquals("var id: Long = 0L", JavaDeclarationPreprocessor.preprocess("long id;"))
    }

    @Test
    fun `translates initialized variables`() {
        assertEquals("var x: String = \"hello\"", JavaDeclarationPreprocessor.preprocess("private String x = \"hello\";"))
        assertEquals("val x: String = \"hello\"", JavaDeclarationPreprocessor.preprocess("private final String x = \"hello\";"))
        assertEquals("var count: Int = 42", JavaDeclarationPreprocessor.preprocess("int count = 42;"))
    }

    @Test
    fun `handles new expressions in initializer`() {
        val result = JavaDeclarationPreprocessor.preprocess("List<String> list = new ArrayList<>();")
        assertEquals("var list: List<String> = ArrayList()", result)
    }

    @Test
    fun `does not modify normal Kotlin code`() {
        assertEquals("val x = 10", JavaDeclarationPreprocessor.preprocess("val x = 10"))
        assertEquals("var name: String = \"hi\"", JavaDeclarationPreprocessor.preprocess("var name: String = \"hi\""))
        assertEquals("fun hello() = 42", JavaDeclarationPreprocessor.preprocess("fun hello() = 42"))
        assertEquals("todoService.findAll()", JavaDeclarationPreprocessor.preprocess("todoService.findAll()"))
        assertEquals("return x", JavaDeclarationPreprocessor.preprocess("return x"))
    }

    @Test
    fun `extracts declaration info`() {
        val info = JavaDeclarationPreprocessor.parseDeclaration("private String x;")
        assertNotNull(info)
        assertEquals("x", info.name)
        assertEquals("String", info.typeName)
        assertEquals("var x: String? = null", info.kotlinCode)

        assertNull(JavaDeclarationPreprocessor.parseDeclaration("val x = 1"))
    }
}
