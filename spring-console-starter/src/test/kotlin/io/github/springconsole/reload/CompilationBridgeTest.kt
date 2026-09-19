package io.github.springconsole.reload

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompilationBridgeTest {

    @Test
    fun `detects a gradle wrapper`(@TempDir dir: File) {
        File(dir, "gradlew").writeText("#!/bin/sh")
        val command = CompilationBridge(dir).command()!!
        assertTrue(command.first().endsWith("gradlew"))
        assertTrue("classes" in command)
    }

    @Test
    fun `finds the wrapper in ancestor directories for multi-module builds`(@TempDir root: File) {
        File(root, "gradlew").writeText("#!/bin/sh")
        val subproject = File(root, "examples/todo-app").apply { mkdirs() }

        val command = CompilationBridge(subproject).command()!!
        assertEquals(File(root, "gradlew").absolutePath, command.first())
    }

    @Test
    fun `detects a maven wrapper`(@TempDir dir: File) {
        File(dir, "mvnw").writeText("#!/bin/sh")
        val command = CompilationBridge(dir).command()!!
        assertTrue(command.first().endsWith("mvnw"))
        assertTrue("compile" in command)
    }

    @Test
    fun `explicit command wins over detection`(@TempDir dir: File) {
        File(dir, "gradlew").writeText("#!/bin/sh")
        val bridge = CompilationBridge(dir, configuredCommand = listOf("make", "build"))
        assertEquals(listOf("make", "build"), bridge.command())
    }

    @Test
    fun `reports unavailable when no build tool exists`(@TempDir dir: File) {
        assertNull(CompilationBridge(dir).command())
        val outcome = CompilationBridge(dir).compile()
        assertTrue(outcome is CompilationBridge.Outcome.Unavailable)
        assertTrue(outcome.reason.contains("spring-console.reload.command"))
    }

    @Test
    fun `successful command reports success with output`(@TempDir dir: File) {
        val bridge = CompilationBridge(dir, configuredCommand = listOf("echo", "compiled fine"))
        val outcome = bridge.compile()
        assertTrue(outcome is CompilationBridge.Outcome.Success, "got: $outcome")
        assertTrue(outcome.output.contains("compiled fine"))
    }

    @Test
    fun `failing command reports structured errors`(@TempDir dir: File) {
        val script = File(dir, "failbuild.sh").apply {
            writeText(
                """
                #!/bin/sh
                echo "e: file://${'$'}PWD/src/Foo.kt:3:9 Unresolved reference: bar"
                exit 1
                """.trimIndent(),
            )
            setExecutable(true)
        }
        val outcome = CompilationBridge(dir, configuredCommand = listOf(script.absolutePath)).compile()
        val failure = outcome as CompilationBridge.Outcome.Failure
        assertEquals(1, failure.errors.size)
        assertEquals(3, failure.errors[0].line)
        assertEquals(9, failure.errors[0].column)
        assertTrue(failure.errors[0].message.contains("Unresolved reference"))
    }
}
