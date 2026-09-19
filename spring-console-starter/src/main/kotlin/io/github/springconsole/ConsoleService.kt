package io.github.springconsole

import io.github.springconsole.api.BeanDetails
import io.github.springconsole.api.BeanSummary
import io.github.springconsole.api.ContextSchema
import io.github.springconsole.api.EvalResult
import io.github.springconsole.binding.ApplicationNamespaceResolver
import io.github.springconsole.binding.BeanBinder
import io.github.springconsole.binding.BoundBean
import io.github.springconsole.engine.EngineHolder
import io.github.springconsole.engine.EvalService
import io.github.springconsole.engine.KotlinReplEngine
import io.github.springconsole.engine.ReplBinding
import io.github.springconsole.introspect.BeanIntrospector
import io.github.springconsole.introspect.ContextSchemaService
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import kotlin.script.experimental.api.KotlinType

/**
 * Facade over one application context: evaluation, bean listing/inspection,
 * and domain schema. The terminal REPL and the MCP server both talk to the
 * console exclusively through this class, so both interfaces observe
 * identical behavior (the "symmetric for humans and agents" USP).
 *
 * A new instance is created per context; a context reload discards the old
 * one together with its REPL snippet state.
 */
class ConsoleService(
    val context: ConfigurableApplicationContext,
    val properties: SpringConsoleProperties,
) : ConsoleOperations, AutoCloseable {

    override val classLoader: ClassLoader?
        get() = context.classLoader

    private val log = LoggerFactory.getLogger(ConsoleService::class.java)

    private val binder = BeanBinder(context, properties.includeInfrastructureBeans)

    private val boundBeans: List<BoundBean> by lazy { binder.boundBeans() }

    private val namespaceResolver = ApplicationNamespaceResolver(context)

    private val engineHolder = EngineHolder {
        val bindings = boundBeans.map { ReplBinding(it.replName, KotlinType(it.type.kotlin), it.instance) } +
            ReplBinding("context", KotlinType(ApplicationContext::class), context)
        val defaultImports = (
            namespaceResolver.unambiguousClassImports +
                namespaceResolver.packageImports +
                listOf("java.time.*", "java.util.*", "io.github.springconsole.extensions.*") +
                properties.defaultImports
        ).distinct()
        KotlinReplEngine(
            bindings = bindings,
            baseClassLoader = context.classLoader ?: Thread.currentThread().contextClassLoader,
            defaultImports = defaultImports,
        )
    }

    private val evalService = EvalService(engineHolder, properties.evalTimeoutMs)

    private val introspector = BeanIntrospector { boundBeans }

    private val schemaService = ContextSchemaService(context) { boundBeans }

    /**
     * Evaluates a Kotlin snippet with permanent consequences against the live
     * application context.
     */
    override fun eval(code: String): EvalResult =
        evalService.eval(code, null)

    override fun eval(code: String, timeoutMs: Long?): EvalResult =
        evalService.eval(code, timeoutMs)

    override fun listBeans(): List<BeanSummary> =
        introspector.listBeans(null, false)

    override fun listBeans(packageFilter: String?): List<BeanSummary> =
        introspector.listBeans(packageFilter, false)

    override fun listBeans(packageFilter: String?, includeProxies: Boolean): List<BeanSummary> =
        introspector.listBeans(packageFilter, includeProxies)

    override fun inspectBean(beanName: String): BeanDetails? = introspector.inspectBean(beanName)

    override fun inspectClass(name: String, type: Class<*>): BeanDetails = introspector.inspectClass(name, type)

    override fun resolveClassBySimpleName(simpleName: String): Class<*>? =
        namespaceResolver.resolveClassBySimpleName(simpleName)

    override fun contextSchema(): ContextSchema = schemaService.schema()

    /**
     * Pre-initializes the script compiler off the startup path.
     *
     * Deliberately routed through [EvalService] rather than calling the engine
     * directly: all compiler work must happen on the evaluation thread (the
     * Kotlin REPL compiler is thread-affine), so warming up elsewhere would
     * corrupt the compiler for the first real snippet.
     */
    fun warmUpAsync() {
        if (!properties.warmup) return
        Thread({
            try {
                evalService.warmUp()
                log.debug("Console engine warmed up")
            } catch (e: Exception) {
                log.debug("Console engine warm-up failed", e)
            }
        }, "spring-console-warmup").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        evalService.close()
    }
}
