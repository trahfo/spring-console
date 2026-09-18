package io.github.springconsole.reload

import io.github.springconsole.ConsoleService
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Everything needed to boot the application again inside this JVM, captured
 * once at first startup and immutable across restarts.
 */
class RestartContext(
    val mainClassName: String,
    val args: Array<String>,
    /** Classpath entries that are directories: the reloadable compiled output. */
    val classpathDirs: List<File>,
    /** The original application classloader; stays the parent of every restart loader. */
    val parentLoader: ClassLoader,
)

/**
 * Restarts the Spring application in place (FR-3.2–3.5): closes the current
 * ApplicationContext without terminating the process, discards the previous
 * restart classloader, and re-runs the application's `main` on a fresh
 * [RestartClassLoader] so newly compiled classes take effect.
 */
class Restarter(
    private val restartContext: RestartContext,
    private val runtime: RestartCoordinator,
) {
    private val log = LoggerFactory.getLogger(Restarter::class.java)

    /** Coordination points the console runtime provides. */
    interface RestartCoordinator {
        /** Marks a restart as in progress; the returned future completes when the new context attaches. */
        fun beginRestart(): CompletableFuture<ConsoleService>

        fun endRestart()

        /** The currently attached context to close, if any. */
        fun currentConsole(): ConsoleService?
    }

    sealed interface Outcome {
        data class Success(val durationMs: Long) : Outcome
        data class Failed(val reason: String, val cause: Throwable? = null) : Outcome
    }

    fun restart(timeoutMs: Long): Outcome {
        val start = System.nanoTime()
        val attached = runtime.beginRestart()
        try {
            runtime.currentConsole()?.let { console ->
                log.info("Closing application context for restart")
                console.context.close()
            }

            val classLoader = RestartClassLoader(
                restartContext.classpathDirs.map { it.toURI().toURL() }.toTypedArray(),
                restartContext.parentLoader,
            )

            val bootThread = Thread({
                try {
                    val mainClass = Class.forName(restartContext.mainClassName, false, classLoader)
                    mainClass
                        .getDeclaredMethod("main", Array<String>::class.java)
                        .invoke(null, restartContext.args)
                } catch (t: Throwable) {
                    val cause = if (t is java.lang.reflect.InvocationTargetException) t.targetException else t
                    log.error("Application restart failed", cause)
                    attached.completeExceptionally(cause)
                }
            }, "spring-console-restart")
            bootThread.contextClassLoader = classLoader
            bootThread.isDaemon = false
            bootThread.start()

            attached.get(timeoutMs, TimeUnit.MILLISECONDS)
            return Outcome.Success((System.nanoTime() - start) / 1_000_000)
        } catch (e: TimeoutException) {
            return Outcome.Failed(
                "Restarted context did not come up within ${timeoutMs}ms. " +
                    "It may still be starting; check the application logs.",
            )
        } catch (e: Exception) {
            val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
            return Outcome.Failed("Restart failed: ${cause.javaClass.simpleName}: ${cause.message}", cause)
        } finally {
            runtime.endRestart()
        }
    }
}
