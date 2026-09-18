package io.github.springconsole.binding

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class NameSanitizerTest {

    @Test
    fun `valid identifiers pass through unchanged`() {
        assertEquals("todoService", NameSanitizer.sanitize("todoService"))
        assertEquals("my_bean_2", NameSanitizer.sanitize("my_bean_2"))
    }

    @Test
    fun `kotlin hard keywords get a trailing underscore`() {
        assertEquals("val_", NameSanitizer.sanitize("val"))
        assertEquals("class_", NameSanitizer.sanitize("class"))
        assertEquals("fun_", NameSanitizer.sanitize("fun"))
        assertEquals("object_", NameSanitizer.sanitize("object"))
    }

    @Test
    fun `symbols become underscores`() {
        assertEquals("jpa_named_queries_0", NameSanitizer.sanitize("jpa.named-queries#0"))
        assertEquals("scopedTarget_myBean", NameSanitizer.sanitize("scopedTarget.myBean"))
    }

    @Test
    fun `leading digits are prefixed`() {
        assertEquals("_1stBean", NameSanitizer.sanitize("1stBean"))
    }

    @Test
    fun `empty names become a single underscore`() {
        assertEquals("_", NameSanitizer.sanitize(""))
    }

    @Test
    fun `non-ascii letters are replaced deterministically`() {
        assertEquals("caf__bean", NameSanitizer.sanitize("café bean"))
    }

    @Test
    fun `collisions get deterministic numeric suffixes`() {
        val taken = setOf("myBean", "myBean_2")
        assertEquals("myBean_3", NameSanitizer.sanitizeUnique("myBean", taken))
        assertEquals("myBean_2", NameSanitizer.sanitizeUnique("my-Bean".replace("-", ""), setOf("myBean")))
    }
}
