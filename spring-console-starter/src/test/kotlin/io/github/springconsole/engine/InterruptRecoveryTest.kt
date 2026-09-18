package io.github.springconsole.engine

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Documents why [EvalService] discards the engine after a timeout: a thread
 * interrupt delivered during snippet evaluation corrupts the REPL compiler's
 * IR state ("Exception during psi2ir" on every later compile), while a fresh
 * engine in the same JVM recovers fully.
 */
class InterruptRecoveryTest {

    @Test
    fun `a fresh engine recovers after an interrupted evaluation`() {
        val engine = KotlinReplEngine(emptyList(), javaClass.classLoader)
        val evaluationStarted = CountDownLatch(1)
        var interruptedOutcome: SnippetOutcome? = null

        val worker = Thread {
            interruptedOutcome = engine.eval("Thread.sleep(30_000)") { evaluationStarted.countDown() }
        }
        worker.start()
        assertTrue(evaluationStarted.await(120, TimeUnit.SECONDS), "evaluation never started")
        Thread.sleep(200)
        worker.interrupt()
        worker.join(10_000)

        val interrupted = assertInstanceOf<SnippetOutcome.RuntimeError>(interruptedOutcome!!)
        assertTrue(interrupted.exception is InterruptedException, "got: ${interrupted.exception}")

        val fresh = KotlinReplEngine(emptyList(), javaClass.classLoader)
        val outcome = fresh.eval("21 * 2")
        assertEquals("42", assertInstanceOf<SnippetOutcome.Success>(outcome).rendered)
    }
}
