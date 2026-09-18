package io.github.springconsole.reload

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class RestartClassLoaderTest {

    private fun compile(dir: File, className: String, source: String): File {
        val sourceFile = File(dir, "$className.java").apply { writeText(source) }
        val compiler = ToolProvider.getSystemJavaCompiler()
        val result = compiler.run(null, null, null, sourceFile.absolutePath)
        check(result == 0) { "javac failed" }
        return dir
    }

    @Test
    fun `loads classes from its directories child-first`(@TempDir dirV1: File, @TempDir dirV2: File) {
        compile(dirV1, "Reloadable", """public class Reloadable { public static String version() { return "v1"; } }""")
        compile(dirV2, "Reloadable", """public class Reloadable { public static String version() { return "v2"; } }""")

        val parent = javaClass.classLoader
        val loaderV1 = RestartClassLoader(arrayOf(dirV1.toURI().toURL()), parent)
        val loaderV2 = RestartClassLoader(arrayOf(dirV2.toURI().toURL()), parent)

        val v1 = Class.forName("Reloadable", true, loaderV1)
        val v2 = Class.forName("Reloadable", true, loaderV2)

        assertSame(loaderV1, v1.classLoader, "class must come from the restart loader, not the parent")
        assertNotEquals(v1, v2, "each restart loader must define its own class")
        assertEquals("v1", v1.getMethod("version").invoke(null))
        assertEquals("v2", v2.getMethod("version").invoke(null))
    }

    @Test
    fun `delegates classes it does not own to the parent`(@TempDir dir: File) {
        val loader = RestartClassLoader(arrayOf(dir.toURI().toURL()), javaClass.classLoader)
        val loaded = Class.forName("io.github.springconsole.reload.RestartClassLoader", true, loader)
        assertSame(RestartClassLoader::class.java, loaded, "framework classes must be shared with the parent")
    }
}
