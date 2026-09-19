package io.github.springconsole.repl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jline.terminal.impl.DumbTerminal
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream

/** Mimics JDK 22+, where `System.console()` exists even when redirected. */
class FakeTerminalConsole(private val terminal: Boolean) {
    fun isTerminal(): Boolean = terminal
}

/** Mimics JDK 21 and earlier, where `Console` has no `isTerminal()`. */
class LegacyConsole

/**
 * Tests for the terminal-selection logic behind [ConsoleRepl]. These cover the
 * regression where a real terminal was reported as "not interactive" because
 * JLine's native system-terminal providers failed and the builder silently fell
 * back to a dumb terminal.
 */
class ConsoleReplTest {

    @Test
    fun `no console means the standard streams are not a terminal`() {
        assertFalse(isStdioTerminal(null))
    }

    @Test
    fun `a legacy console without isTerminal is treated as a terminal`() {
        // JDK <= 21: System.console() is only non-null when a terminal is attached.
        assertTrue(isStdioTerminal(LegacyConsole()))
    }

    @Test
    fun `jdks with isTerminal are honoured`() {
        assertTrue(isStdioTerminal(FakeTerminalConsole(true)))
        assertFalse(isStdioTerminal(FakeTerminalConsole(false)), "redirected console must not look interactive")
    }

    @Test
    fun `terminal type falls back for unset blank or dumb TERM`() {
        assertEquals("xterm-256color", terminalType(null))
        assertEquals("xterm-256color", terminalType(""))
        assertEquals("xterm-256color", terminalType("   "))
        assertEquals("xterm-256color", terminalType("dumb"))
    }

    @Test
    fun `terminal type keeps a real TERM value`() {
        assertEquals("screen-256color", terminalType("screen-256color"))
        assertEquals("xterm", terminalType("xterm"))
    }

    @Test
    fun `a dumb terminal does not start the REPL`() {
        val terminal = CapturingTerminal()
        val repl = ConsoleRepl(
            consoleSupplier = { null },
            terminalFactory = { terminal.terminal },
        )

        assertFalse(repl.startIfInteractive())
        repl.close()
    }

    @Test
    fun `a non-dumb terminal starts the REPL and terminates on end of input`() {
        // CapturingTerminal is dumb, so bypass the interactive check but keep the
        // real terminal object: this exercises the start/stop lifecycle.
        val terminal = CapturingTerminal(":quit\n")
        val repl = ConsoleRepl(
            consoleSupplier = { null },
            terminalFactory = { terminal.terminal },
            requireInteractiveTerminal = false,
            historyFile = null,
        )

        assertTrue(repl.startIfInteractive())
        assertTrue(repl.awaitTermination(15_000), "REPL did not stop:\n${terminal.text()}")
        assertTrue(terminal.text().contains("Spring Console"), terminal.text())
        repl.close()
    }

    @Test
    fun `the REPL thread is non-daemon so a console-only app stays alive`() {
        // A terminal whose input blocks forever (no writer attached) keeps the
        // loop in readLine, where we can observe the thread.
        val terminal = DumbTerminal(PipedInputStream(), ByteArrayOutputStream())
        val repl = ConsoleRepl(
            consoleSupplier = { null },
            terminalFactory = { terminal },
            requireInteractiveTerminal = false,
            historyFile = null,
        )

        try {
            assertTrue(repl.startIfInteractive())
            val worker = Thread.getAllStackTraces().keys.firstOrNull { it.name == "spring-console-repl" }
            assertTrue(worker != null, "REPL thread not found")
            assertFalse(worker!!.isDaemon, "a daemon REPL thread would let a console-only JVM exit immediately")
            assertTrue(worker.isAlive)
        } finally {
            repl.close()
        }
    }
}
