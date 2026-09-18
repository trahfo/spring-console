package io.github.springconsole.engine

import io.github.springconsole.api.EvalResult
import io.github.springconsole.api.EvalStatus
import org.slf4j.LoggerFactory
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The console's evaluation front door: serializes snippet execution, applies
 * the transactional sandbox, captures printed output, enforces timeouts, and
 * renders everything into a deterministic [EvalResult].
 */
class EvalService(
    private val engineHolder: EngineHolder,
    private val transactionalExecutor: TransactionalExecutor,
    private val defaultTimeoutMs: Long = 5_000,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(EvalService::class.java)

    @Volatile
    private var executor: ExecutorService = newExecutor()

    /**
     * Compilation is never interrupted (see [KotlinReplEngine.eval]) and is
     * bounded by the compiler itself; this cap only guards against a
     * pathologically hung compiler.
     */
    private val compilePhaseCapMs: Long = 120_000

    /**
     * Evaluates [code]. [timeoutMs] bounds the *user-code* phase of the
     * snippet; compilation time is not counted against it.
     */
    fun eval(code: String, rollback: Boolean = true, timeoutMs: Long? = null): EvalResult {
        val effectiveTimeout = (timeoutMs ?: defaultTimeoutMs).coerceAtLeast(1)
        val capture = OutputCapture()
        val startNanos = System.nanoTime()
        val evaluationStarted = java.util.concurrent.CountDownLatch(1)

        val future = executor.submit(
            Callable {
                capture.capture {
                    transactionalExecutor.execute(rollback) {
                        engineHolder.engine().eval(code) { evaluationStarted.countDown() }
                    }
                }
            },
        )

        // Phase 1 — compilation: wait until user code starts (or the task ends
        // early on a compile error). Never interrupt this phase: an interrupt
        // inside the compiler's NIO reads would poison its jar-channel caches
        // for the rest of the JVM.
        val compileDeadline = System.nanoTime() + compilePhaseCapMs * 1_000_000
        while (!future.isDone && !evaluationStarted.await(25, TimeUnit.MILLISECONDS)) {
            if (System.nanoTime() > compileDeadline) {
                executor = newExecutor()
                engineHolder.reset()
                log.error("Snippet compilation hung for over {} ms; abandoning its thread", compilePhaseCapMs)
                return EvalResult(
                    status = EvalStatus.TIMEOUT,
                    printedOutput = capture.output(),
                    executionTimeMs = elapsedMs(startNanos),
                    result = "Compilation did not complete within ${compilePhaseCapMs}ms.",
                )
            }
        }

        // Phase 2 — user code: interruption is safe now.
        return try {
            val sandboxed = future.get(effectiveTimeout, TimeUnit.MILLISECONDS)
            toResult(sandboxed.value, sandboxed.rolledBack, capture, elapsedMs(startNanos))
        } catch (e: TimeoutException) {
            future.cancel(true)
            // The evaluation thread may ignore the interrupt; abandon it so
            // subsequent evaluations are not queued behind a runaway snippet.
            executor = newExecutor()
            // An interrupt mid-evaluation corrupts the REPL compiler's IR
            // state (psi2ir crashes on every later snippet), so the engine —
            // including accumulated snippet state — must be discarded.
            engineHolder.reset()
            log.warn("Snippet evaluation timed out after {} ms; REPL session state was reset", effectiveTimeout)
            EvalResult(
                status = EvalStatus.TIMEOUT,
                printedOutput = capture.output(),
                executionTimeMs = elapsedMs(startNanos),
                result = "Evaluation exceeded ${effectiveTimeout}ms and was cancelled. " +
                    "The snippet thread was interrupted but may still be running. " +
                    "REPL session state (previously declared values) was reset.",
            )
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            log.error("Unexpected console engine failure", cause)
            EvalResult(
                status = EvalStatus.RUNTIME_EXCEPTION,
                printedOutput = capture.output(),
                executionTimeMs = elapsedMs(startNanos),
                exception = StackTracePruner.details(cause),
            )
        }
    }

    private fun toResult(
        outcome: SnippetOutcome,
        rolledBack: Boolean,
        capture: OutputCapture,
        executionTimeMs: Long,
    ): EvalResult = when (outcome) {
        is SnippetOutcome.Success -> EvalResult(
            status = EvalStatus.SUCCESS,
            result = outcome.rendered,
            printedOutput = capture.output(),
            executionTimeMs = executionTimeMs,
            transactionRolledBack = rolledBack,
        )
        is SnippetOutcome.CompileError -> EvalResult(
            status = EvalStatus.COMPILATION_ERROR,
            printedOutput = capture.output(),
            executionTimeMs = executionTimeMs,
            compilationErrors = outcome.errors,
        )
        is SnippetOutcome.RuntimeError -> EvalResult(
            status = EvalStatus.RUNTIME_EXCEPTION,
            printedOutput = capture.output(),
            executionTimeMs = executionTimeMs,
            transactionRolledBack = rolledBack,
            exception = StackTracePruner.details(outcome.exception),
        )
        is SnippetOutcome.EngineError -> EvalResult(
            status = EvalStatus.RUNTIME_EXCEPTION,
            printedOutput = capture.output(),
            executionTimeMs = executionTimeMs,
            exception = io.github.springconsole.api.ExceptionDetails(
                type = "io.github.springconsole.EngineFailure",
                message = outcome.message,
                stackTrace = emptyList(),
            ),
        )
    }

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    private fun newExecutor(): ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "spring-console-eval").apply { isDaemon = true }
    }

    override fun close() {
        executor.shutdownNow()
    }
}
