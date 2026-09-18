package io.github.springconsole.reload

import io.github.springconsole.api.CompilationError
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the project's own build tool for incremental recompilation (FR-3.1).
 * The Gradle/Maven wrapper reuses its daemon across invocations, so warm
 * compile cycles stay fast without this library embedding a compiler.
 */
class CompilationBridge(
    private val projectDir: File,
    private val configuredCommand: List<String> = emptyList(),
    private val timeoutMs: Long = 120_000,
) {
    private val log = LoggerFactory.getLogger(CompilationBridge::class.java)

    sealed interface Outcome {
        data class Success(val durationMs: Long, val output: String) : Outcome
        data class Failure(val errors: List<CompilationError>, val output: String) : Outcome
        data class Unavailable(val reason: String) : Outcome
    }

    fun compile(): Outcome {
        val command = command()
            ?: return Outcome.Unavailable(
                "No build tool found in $projectDir (looked for gradlew, mvnw, build.gradle[.kts], pom.xml). " +
                    "Set spring-console.reload.command explicitly.",
            )

        log.info("Recompiling with: {}", command.joinToString(" "))
        val start = System.nanoTime()
        val process = try {
            ProcessBuilder(command)
                .directory(projectDir)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            return Outcome.Unavailable("Could not start build tool ${command.first()}: ${e.message}")
        }

        val output = StringBuilder()
        val readerThread = Thread {
            process.inputStream.bufferedReader().forEachLine { line ->
                if (output.length < MAX_OUTPUT_CHARS) output.append(line).append('\n')
            }
        }.apply {
            isDaemon = true
            start()
        }

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            return Outcome.Unavailable("Build timed out after ${timeoutMs}ms")
        }
        readerThread.join(2_000)
        val durationMs = (System.nanoTime() - start) / 1_000_000
        val text = output.toString()

        return if (process.exitValue() == 0) {
            Outcome.Success(durationMs, text)
        } else {
            val errors = BuildErrorParser.parse(text)
            Outcome.Failure(
                errors.ifEmpty {
                    listOf(CompilationError(-1, -1, "Build failed (exit ${process.exitValue()}); see buildOutput"))
                },
                text.takeLast(MAX_REPORTED_OUTPUT_CHARS),
            )
        }
    }

    /** The build command: configured explicitly, or detected from the project layout. */
    fun command(): List<String>? {
        if (configuredCommand.isNotEmpty()) return configuredCommand

        val gradlew = File(projectDir, if (isWindows()) "gradlew.bat" else "gradlew")
        if (gradlew.isFile) {
            return listOf(gradlew.absolutePath, "classes", "--console=plain", "-q")
        }
        val mvnw = File(projectDir, if (isWindows()) "mvnw.cmd" else "mvnw")
        if (mvnw.isFile) {
            return listOf(mvnw.absolutePath, "-q", "compile")
        }
        if (File(projectDir, "build.gradle.kts").isFile || File(projectDir, "build.gradle").isFile) {
            return listOf("gradle", "classes", "--console=plain", "-q")
        }
        if (File(projectDir, "pom.xml").isFile) {
            return listOf("mvn", "-q", "compile")
        }
        return null
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")

    companion object {
        private const val MAX_OUTPUT_CHARS = 512 * 1024
        private const val MAX_REPORTED_OUTPUT_CHARS = 8 * 1024
    }
}
