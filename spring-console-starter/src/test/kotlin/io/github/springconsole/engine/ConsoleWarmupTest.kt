package io.github.springconsole.engine

import io.github.springconsole.ConsoleService
import io.github.springconsole.SpringConsoleProperties
import io.github.springconsole.api.EvalStatus
import io.github.springconsole.fixture.ConsoleTestApp
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext
import kotlin.test.assertEquals

/**
 * Regression test for the warm-up race that broke the first interactive
 * snippet.
 *
 * `ConsoleService.warmUpAsync()` used to call the scripting engine directly on
 * its own `spring-console-warmup` thread while real evaluations ran on
 * `spring-console-eval`. The Kotlin REPL compiler is thread-affine, so the
 * first snippet compiled on the other thread failed with
 *
 * ```
 * Backend Internal error: Exception during psi2ir
 *   java.lang.IllegalStateException at SymbolTableSlice$Scoped.noScope
 * ```
 *
 * Warm-up now goes through [EvalService], which owns the single evaluation
 * thread. These tests exercise that ordering: start warm-up, immediately
 * evaluate, and require a normal result.
 */
@SpringBootTest(
    classes = [ConsoleTestApp::class],
    properties = [
        "spring-console.enabled=false", // this test drives its own ConsoleService instance
        "spring.datasource.url=jdbc:h2:mem:warmup-it;DB_CLOSE_DELAY=-1",
        "logging.level.org.hibernate=warn",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleWarmupTest {

    @Autowired
    lateinit var context: ConfigurableApplicationContext

    private val opened = mutableListOf<ConsoleService>()

    private fun console(warmup: Boolean = true): ConsoleService =
        ConsoleService(context, SpringConsoleProperties().apply { this.warmup = warmup }).also { opened += it }

    @AfterAll
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
    }

    @Test
    fun `an evaluation racing the warm-up still compiles`() {
        val console = console()

        console.warmUpAsync()
        val first = console.eval("1 + 1")

        assertEquals(EvalStatus.SUCCESS, first.status, "first snippet failed: $first")
        assertEquals("2", first.result)
    }

    @Test
    fun `warm-up does not corrupt later snippets`() {
        repeat(3) {
            val console = console()
            console.warmUpAsync()

            assertEquals(EvalStatus.SUCCESS, console.eval("2 * 3").status)
            assertEquals(EvalStatus.SUCCESS, console.eval("val warmupProbe = 7").status)
            assertEquals("7", console.eval("warmupProbe").result)
        }
    }

    @Test
    fun `warm-up can be disabled`() {
        val console = console(warmup = false)

        console.warmUpAsync() // no-op
        val result = console.eval("\"cold but fine\"")

        assertEquals(EvalStatus.SUCCESS, result.status, "$result")
        assertEquals("cold but fine", result.result)
    }
}
