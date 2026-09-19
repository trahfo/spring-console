package io.github.springconsole.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [withCompilerHint]: the message decoration that turns the cryptic
 * "Unable to find kotlin stdlib" failure (running from a Spring Boot executable
 * jar, whose nested jars the runtime Kotlin compiler cannot read) into
 * something actionable.
 */
class CompilerHintTest {

    @Test
    fun `stdlib failures explain the exploded-classpath requirement`() {
        val hinted = withCompilerHint(
            "Unable to initialize repl compiler: Unable to find kotlin stdlib, " +
                "please specify it explicitly via \"kotlin.java.stdlib.jar\" property",
        )

        assertTrue(hinted.contains("Unable to find kotlin stdlib"), hinted)
        assertTrue(hinted.contains("exploded classpath"), hinted)
        assertTrue(hinted.contains("jarmode=tools"), hinted)
    }

    @Test
    fun `unrelated compiler messages are unchanged`() {
        val message = "Unresolved reference: findByEmail"

        assertEquals(message, withCompilerHint(message))
    }
}
