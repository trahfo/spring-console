package io.github.springconsole.autoconfigure

import io.github.springconsole.ConsoleRuntime
import io.github.springconsole.fixture.ConsoleTestApp
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

@SpringBootTest(
    classes = [ConsoleTestApp::class],
    properties = [
        "spring-console.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:disabled-it;DB_CLOSE_DELAY=-1",
    ],
)
class SpringConsoleDisabledTest {

    @Autowired
    lateinit var context: ConfigurableApplicationContext

    @Test
    fun `spring-console enabled=false keeps the console out of the context`() {
        assertTrue(context.getBeansOfType(ConsoleBootstrap::class.java).isEmpty())
        ConsoleRuntime.console()?.let { assertNotSame(context, it.context) }
    }
}
