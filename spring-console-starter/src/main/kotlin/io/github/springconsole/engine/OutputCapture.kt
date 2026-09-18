package io.github.springconsole.engine

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/**
 * Captures `System.out`/`System.err` written while a block runs, so that
 * snippet print output can be reported separately from the snippet's return
 * value (determinism NFR: logging output is isolated from return values).
 *
 * The JVM's standard streams are global, so output written by unrelated
 * threads during an evaluation is captured as well. Evaluations are
 * serialized, which keeps this window small.
 */
class OutputCapture {
    private val buffer = ByteArrayOutputStream()

    fun <T> capture(block: () -> T): T {
        val replacement = PrintStream(buffer, true, StandardCharsets.UTF_8)
        val previousOut = System.out
        val previousErr = System.err
        System.setOut(replacement)
        System.setErr(replacement)
        try {
            return block()
        } finally {
            replacement.flush()
            System.setOut(previousOut)
            System.setErr(previousErr)
        }
    }

    fun output(): String = synchronized(buffer) { buffer.toString(StandardCharsets.UTF_8) }
}
