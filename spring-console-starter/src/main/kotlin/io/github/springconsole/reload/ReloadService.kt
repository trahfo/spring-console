package io.github.springconsole.reload

import io.github.springconsole.api.ReloadResult
import io.github.springconsole.api.ReloadStatus
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates the full `reload` cycle (FR-3): incremental compile via the
 * project build tool, in-process context restart on a fresh classloader, and
 * console re-binding. Lives outside any ApplicationContext so it survives the
 * restart it performs.
 */
class ReloadService(
    private val bridge: CompilationBridge,
    private val restarter: Restarter,
    private val restartTimeoutMs: Long = 60_000,
) {
    private val log = LoggerFactory.getLogger(ReloadService::class.java)

    private val inProgress = AtomicBoolean(false)

    fun reload(recompile: Boolean): ReloadResult {
        if (!inProgress.compareAndSet(false, true)) {
            return ReloadResult(ReloadStatus.RESTART_FAILED, "A reload is already in progress.")
        }
        try {
            val totalStart = System.nanoTime()
            var compileMs = 0L

            if (recompile) {
                when (val outcome = bridge.compile()) {
                    is CompilationBridge.Outcome.Unavailable ->
                        return ReloadResult(ReloadStatus.NOT_SUPPORTED, outcome.reason)
                    is CompilationBridge.Outcome.Failure ->
                        return ReloadResult(
                            status = ReloadStatus.COMPILATION_ERROR,
                            message = "Compilation failed with ${outcome.errors.size} error(s); context was not restarted.",
                            compilationErrors = outcome.errors,
                            buildOutput = outcome.output,
                            compilationTimeMs = elapsedMs(totalStart),
                            totalTimeMs = elapsedMs(totalStart),
                        )
                    is CompilationBridge.Outcome.Success -> {
                        compileMs = outcome.durationMs
                        log.info("Compilation finished in {} ms", compileMs)
                    }
                }
            }

            val restartStart = System.nanoTime()
            return when (val outcome = restarter.restart(restartTimeoutMs)) {
                is Restarter.Outcome.Success -> ReloadResult(
                    status = ReloadStatus.SUCCESS,
                    message = "Reload complete: compiled in ${compileMs}ms, context restarted in ${outcome.durationMs}ms. " +
                        "REPL snippet state was reset.",
                    compilationTimeMs = compileMs,
                    restartTimeMs = outcome.durationMs,
                    totalTimeMs = elapsedMs(totalStart),
                )
                is Restarter.Outcome.Failed -> ReloadResult(
                    status = ReloadStatus.RESTART_FAILED,
                    message = outcome.reason,
                    compilationTimeMs = compileMs,
                    restartTimeMs = elapsedMs(restartStart),
                    totalTimeMs = elapsedMs(totalStart),
                )
            }
        } finally {
            inProgress.set(false)
        }
    }

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000
}
