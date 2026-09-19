package io.github.springconsole.engine

import io.github.springconsole.ConsoleService
import io.github.springconsole.SpringConsoleProperties
import io.github.springconsole.fixture.ConsoleTestApp
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext
import java.io.File
import java.io.PrintWriter

@SpringBootTest(
    classes = [ConsoleTestApp::class],
    properties = [
        "spring-console.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:corrupt-it;DB_CLOSE_DELAY=-1",
    ],
)
class CorruptionReproTest {

    @Autowired
    lateinit var context: ConfigurableApplicationContext

    @Test
    fun `reproduce psi2ir corruption after compile error`() {
        val out = PrintWriter(File(System.getProperty("java.io.tmpdir"), "corruption-debug.txt"))
        out.use { w ->
            val console = ConsoleService(context, SpringConsoleProperties().apply { warmup = true })
            // mirror live sequence: warmup on its own thread first
            val warmupThread = Thread { console.eval("0") }
            warmupThread.start()
            warmupThread.join()

            w.println("1 error eval: ${console.eval("noteService.noSuchMethod()").status}")
            val third = console.eval("\"still fine\"")
            w.println("2 follow-up: ${third.status} ${third.result ?: third.compilationErrors?.firstOrNull()?.message?.take(80)}")
            val fourth = console.eval("noteService.count()")
            w.println("3 known-symbol: ${fourth.status} ${fourth.result ?: fourth.compilationErrors?.firstOrNull()?.message?.take(80)}")
            console.close()
        }
    }
}
