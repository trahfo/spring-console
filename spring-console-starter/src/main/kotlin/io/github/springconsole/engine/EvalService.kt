package io.github.springconsole.engine

import io.github.springconsole.api.EvalResult
import io.github.springconsole.api.EvalStatus
import io.github.springconsole.binding.HibernateProxyHelper
import org.slf4j.LoggerFactory
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The console's evaluation front door: serializes snippet execution, executes
 * with permanent consequences directly against the live context, captures
 * printed output, enforces timeouts, and renders everything into a
 * deterministic [EvalResult].
 */
class EvalService(
    private val engineHolder: EngineHolder,
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
    fun eval(code: String, timeoutMs: Long? = null): EvalResult {
        val effectiveTimeout = (timeoutMs ?: defaultTimeoutMs).coerceAtLeast(1)
        val capture = OutputCapture()
        val startNanos = System.nanoTime()
        val evaluationStarted = java.util.concurrent.CountDownLatch(1)

        val future = executor.submit(
            Callable {
                capture.capture {
                    val outcome = engineHolder.engine().eval(code) { evaluationStarted.countDown() }
                    if (outcome is SnippetOutcome.Success) {
                        val unproxied = HibernateProxyHelper.initializeAndUnwrap(outcome.value)
                        if (unproxied !== outcome.value) {
                            outcome.copy(value = unproxied)
                        } else outcome
                    } else outcome
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
            val outcome = future.get(effectiveTimeout, TimeUnit.MILLISECONDS)
            toResult(outcome, capture, elapsedMs(startNanos))
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
        capture: OutputCapture,
        executionTimeMs: Long,
    ): EvalResult = when (outcome) {
        is SnippetOutcome.Success -> EvalResult(
            status = EvalStatus.SUCCESS,
            result = outcome.rendered,
            rawValue = outcome.value,
            printedOutput = capture.output(),
            executionTimeMs = executionTimeMs,
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

    /**
     * Compiles and runs a trivial snippet to pay for the one-time script
     * compiler initialization.
     *
     * Runs on the **same single thread** as every later [eval]: the Kotlin REPL
     * compiler keeps thread-affine state, so warming up on another thread (as
     * this used to) leaves the compiler unusable and the first real snippet
     * fails with `Backend Internal error: Exception during psi2ir`
     * (`SymbolTableSlice$Scoped.noScope`).
     */
    fun warmUp(timeoutMs: Long = 60_000) {
        try {
            executor.submit(Callable { engineHolder.engine().eval("0") })
                .get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            log.debug("Console engine warm-up failed", e)
        }
    }

    private fun newExecutor(): ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "spring-console-eval").apply { isDaemon = true }
    }

    override fun close() {
        executor.shutdownNow()
    }
}
