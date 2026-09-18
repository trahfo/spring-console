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

    fun eval(code: String, rollback: Boolean = true, timeoutMs: Long? = null): EvalResult {
        val effectiveTimeout = (timeoutMs ?: defaultTimeoutMs).coerceAtLeast(1)
        val capture = OutputCapture()
        val startNanos = System.nanoTime()

        val future = executor.submit(
            Callable {
                capture.capture {
                    transactionalExecutor.execute(rollback) {
                        engineHolder.engine().eval(code)
                    }
                }
            },
        )

        return try {
            val sandboxed = future.get(effectiveTimeout, TimeUnit.MILLISECONDS)
            toResult(sandboxed.value, sandboxed.rolledBack, capture, elapsedMs(startNanos))
        } catch (e: TimeoutException) {
            future.cancel(true)
            // The evaluation thread may still be running; abandon it so subsequent
            // evaluations are not queued behind a runaway snippet.
            executor = newExecutor()
            log.warn("Snippet evaluation timed out after {} ms", effectiveTimeout)
            EvalResult(
                status = EvalStatus.TIMEOUT,
                printedOutput = capture.output(),
                executionTimeMs = elapsedMs(startNanos),
                result = "Evaluation exceeded ${effectiveTimeout}ms and was cancelled. " +
                    "The snippet thread was interrupted but may still be running.",
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
