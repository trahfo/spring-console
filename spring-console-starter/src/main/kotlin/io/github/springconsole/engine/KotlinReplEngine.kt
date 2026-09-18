package io.github.springconsole.engine

import io.github.springconsole.api.CompilationError
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.KJvmReplCompilerBase
import org.jetbrains.kotlin.scripting.compiler.plugin.repl.ReplCodeAnalyzerBase
import java.util.concurrent.atomic.AtomicInteger
import kotlin.script.experimental.api.KotlinType
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.providedProperties
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.BasicJvmReplEvaluator
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration
import kotlin.script.experimental.jvm.dependenciesFromClassloader
import kotlin.script.experimental.jvm.jvm

/**
 * A single named value exposed to the REPL scope, typed for the script
 * compiler via [KotlinType] (FR-1.1).
 */
class ReplBinding(
    val name: String,
    val type: KotlinType,
    val value: Any?,
)

/** Low-level outcome of compiling and evaluating one snippet. */
sealed interface SnippetOutcome {
    /** Snippet compiled and ran; [rendered] is the string form of its value, null for Unit. */
    data class Success(val rendered: String?) : SnippetOutcome

    data class CompileError(val errors: List<CompilationError>) : SnippetOutcome

    data class RuntimeError(val exception: Throwable) : SnippetOutcome

    /** The scripting infrastructure itself failed (not user code). */
    data class EngineError(val message: String) : SnippetOutcome
}

/**
 * Stateful Kotlin REPL built on the Kotlin scripting compiler
 * ([KJvmReplCompilerBase]) and [BasicJvmReplEvaluator].
 *
 * Snippets share state: a `val` declared in one evaluation is visible to the
 * next. Beans are injected as `providedProperties`, so scripts reference them
 * as plain typed variables. Instances of this class are discarded wholesale on
 * context reload to purge stale snippet classes (FR-3.5).
 */
class KotlinReplEngine(
    private val bindings: List<ReplBinding>,
    private val baseClassLoader: ClassLoader,
    defaultImports: List<String> = emptyList(),
) {
    private val snippetCounter = AtomicInteger(1)
    private val hostConfiguration = defaultJvmScriptingHostConfiguration
    private val compiler = KJvmReplCompilerBase<ReplCodeAnalyzerBase>(hostConfiguration)
    private val evaluator = BasicJvmReplEvaluator()

    private val compilationConfiguration = ScriptCompilationConfiguration {
        jvm {
            dependenciesFromClassloader(classLoader = baseClassLoader, wholeClasspath = true)
        }
        if (bindings.isNotEmpty()) {
            providedProperties(*bindings.map { it.name to it.type }.toTypedArray())
        }
        if (defaultImports.isNotEmpty()) {
            defaultImports(defaultImports)
        }
    }

    private val evaluationConfiguration = ScriptEvaluationConfiguration {
        if (bindings.isNotEmpty()) {
            providedProperties(*bindings.map { it.name to it.value }.toTypedArray())
        }
        jvm {
            baseClassLoader(this@KotlinReplEngine.baseClassLoader)
        }
    }

    /**
     * Compiles and evaluates one snippet. Serialized: the underlying REPL
     * state is not safe for concurrent use.
     */
    @Synchronized
    fun eval(code: String): SnippetOutcome {
        val snippetNo = snippetCounter.getAndIncrement()
        val source = code.toScriptSource("Snippet_$snippetNo.kts")

        val compiled = when (val result = runBlocking { compiler.compile(listOf(source), compilationConfiguration) }) {
            is ResultWithDiagnostics.Failure -> return SnippetOutcome.CompileError(result.reports.toCompilationErrors())
            is ResultWithDiagnostics.Success -> result.value
        }

        return when (val result = runBlocking { evaluator.eval(compiled, evaluationConfiguration) }) {
            is ResultWithDiagnostics.Failure -> SnippetOutcome.EngineError(
                result.reports.joinToString("; ") { it.message }.ifEmpty { "Unknown evaluation failure" },
            )
            is ResultWithDiagnostics.Success -> when (val value = result.value.get().result) {
                is ResultValue.Value -> SnippetOutcome.Success(renderValue(value.value))
                is ResultValue.Unit -> SnippetOutcome.Success(null)
                is ResultValue.Error -> SnippetOutcome.RuntimeError(value.error)
                ResultValue.NotEvaluated -> SnippetOutcome.EngineError("Snippet was not evaluated")
            }
        }
    }

    private fun renderValue(value: Any?): String = when (value) {
        null -> "null"
        else -> try {
            value.toString()
        } catch (e: Exception) {
            "<toString() failed: ${e.javaClass.simpleName}: ${e.message}>"
        }
    }

    private fun List<ScriptDiagnostic>.toCompilationErrors(): List<CompilationError> {
        val errors = filter { it.severity >= ScriptDiagnostic.Severity.ERROR }.map {
            CompilationError(
                line = it.location?.start?.line ?: -1,
                column = it.location?.start?.col ?: -1,
                message = it.message,
                severity = it.severity.name,
            )
        }
        if (errors.isNotEmpty()) return errors
        // A failure without ERROR-severity diagnostics: fall back to whatever the compiler reported.
        return listOf(
            CompilationError(
                line = -1,
                column = -1,
                message = joinToString("; ") { it.message }.ifEmpty { "Unknown compilation failure" },
            ),
        )
    }
}
