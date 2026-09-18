package io.github.springconsole.api

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Outcome status of a single console evaluation.
 */
enum class EvalStatus {
    SUCCESS,
    COMPILATION_ERROR,
    RUNTIME_EXCEPTION,
    TIMEOUT,
}

/**
 * A single structured compiler diagnostic, line- and column-accurate so that
 * agents can self-correct without parsing raw compiler logs (FR-4.1).
 */
data class CompilationError(
    val line: Int,
    val column: Int,
    val message: String,
    val severity: String = "ERROR",
)

/**
 * A runtime exception, reduced to the frames that matter: Spring framework
 * internals (reflection delegates, interceptors, generated proxies) are
 * stripped to minimize token consumption while retaining root-cause domain
 * frames (FR-4.2).
 */
data class ExceptionDetails(
    val type: String,
    val message: String?,
    val stackTrace: List<String>,
)

/**
 * The deterministic result payload of an `eval` invocation, returned both to
 * the terminal REPL renderer and (as JSON) to MCP clients.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class EvalResult(
    val status: EvalStatus,
    /** String representation of the evaluated expression's value, if any. */
    val result: String? = null,
    /** stdout/stderr captured while the snippet executed. */
    val printedOutput: String = "",
    val executionTimeMs: Long = 0,
    /** True when the snippet ran inside a transaction that was rolled back. */
    val transactionRolledBack: Boolean = false,
    val compilationErrors: List<CompilationError>? = null,
    val exception: ExceptionDetails? = null,
)

/** Summary of a bean registered in the ApplicationContext. */
data class BeanSummary(
    /** The original Spring bean name. */
    val name: String,
    /** The identifier under which the bean is bound in the REPL scope (sanitized/aliased). */
    val replName: String,
    /** Fully-qualified unproxied type. */
    val type: String,
    val scope: String,
    /** True if Spring wraps this bean in an AOP proxy (CGLIB or JDK dynamic). */
    val proxied: Boolean,
)

/** A single method signature on an inspected bean. */
data class MethodSignature(
    val name: String,
    val parameters: List<ParameterInfo>,
    val returnType: String,
    val declaringClass: String,
)

data class ParameterInfo(
    val name: String,
    val type: String,
)

/** A readable bean property (getter-derived). */
data class PropertyInfo(
    val name: String,
    val type: String,
    val readable: Boolean,
    val writable: Boolean,
)

/** Full introspection payload for a single bean (MCP tool: inspect_bean). */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class BeanDetails(
    val name: String,
    val replName: String,
    /** Fully-qualified type after unwrapping any AOP proxy. */
    val targetType: String,
    /** The runtime class as Spring exposes it (may be a proxy class). */
    val runtimeType: String,
    val proxied: Boolean,
    val scope: String,
    val interfaces: List<String>,
    val methods: List<MethodSignature>,
    val properties: List<PropertyInfo>,
)

/** Result of a reload (recompile + context restart) cycle. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ReloadResult(
    val status: ReloadStatus,
    /** Human-readable summary, e.g. "Recompiled 1 module, context refreshed in 1.8s". */
    val message: String,
    val compilationTimeMs: Long = 0,
    val restartTimeMs: Long = 0,
    val totalTimeMs: Long = 0,
    val compilationErrors: List<CompilationError>? = null,
    /** Raw (truncated) build tool output; present only on compilation failure. */
    val buildOutput: String? = null,
)

enum class ReloadStatus {
    SUCCESS,
    COMPILATION_ERROR,
    RESTART_FAILED,
    NOT_SUPPORTED,
}

/** Schema of the running application's domain (MCP tool: get_context_schema). */
data class ContextSchema(
    val entities: List<EntityInfo>,
    val repositories: List<RepositoryInfo>,
    val services: List<ServiceInfo>,
)

data class EntityInfo(
    val name: String,
    val type: String,
    val attributes: List<AttributeInfo>,
)

data class AttributeInfo(
    val name: String,
    val type: String,
    val id: Boolean = false,
)

data class RepositoryInfo(
    val beanName: String,
    val replName: String,
    val type: String,
    /** The managed domain type, when it can be resolved. */
    val domainType: String? = null,
    val methods: List<String>,
)

data class ServiceInfo(
    val beanName: String,
    val replName: String,
    val type: String,
)
