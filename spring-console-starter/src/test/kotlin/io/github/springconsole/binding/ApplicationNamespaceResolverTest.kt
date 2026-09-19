package io.github.springconsole.binding

import io.github.springconsole.fixture.ConsoleTestApp
import io.github.springconsole.fixture.Note
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(
    classes = [ConsoleTestApp::class],
    properties = [
        "spring-console.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:ns-test;DB_CLOSE_DELAY=-1",
    ],
)
class ApplicationNamespaceResolverTest {

    @Autowired
    lateinit var context: ConfigurableApplicationContext

    @Test
    fun `discovers project classes and unambiguous imports`() {
        val resolver = ApplicationNamespaceResolver(context)

        // Note is an entity in the test fixture
        val noteClass = resolver.resolveClassBySimpleName("Note")
        assertNotNull(noteClass, "Should resolve Note class")
        assertEquals(Note::class.java, noteClass)

        // unambiguousClassImports should contain Note
        assertTrue(
            resolver.unambiguousClassImports.contains("io.github.springconsole.fixture.Note"),
            "unambiguousClassImports should include Note: ${resolver.unambiguousClassImports}",
        )

        // packageImports should contain the fixture package
        assertTrue(
            resolver.packageImports.contains("io.github.springconsole.fixture.*"),
            "packageImports should include fixture: ${resolver.packageImports}",
        )
    }
}
