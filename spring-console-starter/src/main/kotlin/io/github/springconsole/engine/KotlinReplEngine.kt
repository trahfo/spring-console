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
    /**
     * Snippet compiled and ran.
     *
     * @param rendered the string form of its value, null for Unit.
     * @param value the value itself, used by the terminal renderer for a
     *   structured view. `Unit` for a statement, `null` for a null result.
     */
    data class Success(val rendered: String?, val value: Any?) : SnippetOutcome

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

    private val stateLock = java.util.concurrent.locks.ReentrantLock()

    /**
     * Compiles and evaluates one snippet. The REPL state is not safe for
     * concurrent use, so evaluations are serialized on [stateLock]; when a
     * previous (runaway) snippet still holds the lock, this fails fast rather
     * than queueing forever.
     *
     * [onEvaluationStart] fires after compilation succeeds, immediately before
     * user code runs. Callers use it to scope cancellation to user code only:
     * interrupting a thread inside the Kotlin compiler closes the compiler's
     * shared classpath jar channels (`ClosedByInterruptException`) and
     * permanently poisons compilation for the whole JVM.
     */
    fun eval(code: String, onEvaluationStart: () -> Unit = {}): SnippetOutcome {
        if (!stateLock.tryLock(5, java.util.concurrent.TimeUnit.SECONDS)) {
            return SnippetOutcome.EngineError(
                "A previous evaluation is still running (possibly a runaway snippet that ignored interruption). " +
                    "Retry once it finishes, or restart the application.",
            )
        }
        try {
            return doEval(code, onEvaluationStart)
        } finally {
            stateLock.unlock()
        }
    }

    private fun doEval(code: String, onEvaluationStart: () -> Unit): SnippetOutcome {
        val preprocessedCode = JavaDeclarationPreprocessor.preprocess(code)
        val snippetNo = snippetCounter.getAndIncrement()
        val source = preprocessedCode.toScriptSource("Snippet_$snippetNo.kts")

        val compiled = when (val result = runBlocking { compiler.compile(listOf(source), compilationConfiguration) }) {
            is ResultWithDiagnostics.Failure -> return SnippetOutcome.CompileError(result.reports.toCompilationErrors())
            is ResultWithDiagnostics.Success -> result.value
        }

        onEvaluationStart()
        return when (val result = runBlocking { evaluator.eval(compiled, evaluationConfiguration) }) {
            is ResultWithDiagnostics.Failure -> SnippetOutcome.EngineError(
                result.reports.joinToString("; ") { it.message }.ifEmpty { "Unknown evaluation failure" },
            )
            is ResultWithDiagnostics.Success -> {
                val evalVal = result.value.get()
                initializeScriptInstanceProxies(evalVal)
                when (val value = evalVal.result) {
                    is ResultValue.Value -> {
                        val unproxied = io.github.springconsole.binding.HibernateProxyHelper.initializeAndUnwrap(value.value)
                        SnippetOutcome.Success(renderValue(unproxied), unproxied)
                    }
                    is ResultValue.Unit -> SnippetOutcome.Success(null, Unit)
                    is ResultValue.Error -> SnippetOutcome.RuntimeError(value.error)
                    ResultValue.NotEvaluated -> SnippetOutcome.EngineError("Snippet was not evaluated")
                }
            }
        }
    }

    private fun initializeScriptInstanceProxies(evalVal: Any?) {
        if (evalVal == null) return
        try {
            val scriptInstanceMethod = evalVal.javaClass.methods.firstOrNull { it.name == "getScriptInstance" } ?: return
            val instance = scriptInstanceMethod.invoke(evalVal) ?: return
            for (field in instance.javaClass.declaredFields) {
                field.isAccessible = true
                val fieldValue = field.get(instance)
                if (io.github.springconsole.binding.HibernateProxyHelper.isHibernateProxy(fieldValue)) {
                    val unproxied = io.github.springconsole.binding.HibernateProxyHelper.initializeAndUnwrap(fieldValue)
                    if (unproxied !== fieldValue) {
                        try {
                            field.set(instance, unproxied)
                        } catch (ignored: Exception) {
                        }
                    }
                }
            }
        } catch (ignored: Exception) {
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
                message = withCompilerHint(it.message),
                severity = it.severity.name,
            )
        }
        if (errors.isNotEmpty()) return errors
        // A failure without ERROR-severity diagnostics: fall back to whatever the compiler reported.
        return listOf(
            CompilationError(
                line = -1,
                column = -1,
                message = withCompilerHint(joinToString("; ") { it.message }.ifEmpty { "Unknown compilation failure" }),
            ),
        )
    }
}

/**
 * Appends an actionable explanation when the scripting compiler cannot find the
 * Kotlin standard library.
 *
 * That specific failure happens when the application runs from a **Spring Boot
 * executable jar**: the jar nests `kotlin-stdlib` (and the application classes)
 * inside `BOOT-INF/lib`, and the runtime Kotlin compiler can only read them as
 * real files on disk. Without the hint, users see a bare
 * "Unable to find kotlin stdlib, please specify it explicitly via
 * \"kotlin.java.stdlib.jar\"" and have no idea how to fix it.
 */
internal fun withCompilerHint(message: String): String =
    if (message.contains("Unable to find kotlin stdlib")) "$message $STDLIB_HINT" else message

private const val STDLIB_HINT =
    "(The runtime Kotlin compiler needs the Kotlin stdlib and the application classes as real files, " +
        "but a Spring Boot executable jar nests them inside BOOT-INF/lib. Run the application from an " +
        "exploded classpath instead — e.g. a Gradle-generated start script / `java -cp <runtime classpath>` — " +
        "or extract the jar first: `java -Djarmode=tools -jar app.jar extract` and run the extracted " +
        "`app/app.jar`.)"
