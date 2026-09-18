package io.github.springconsole

import io.github.springconsole.api.BeanDetails
import io.github.springconsole.api.BeanSummary
import io.github.springconsole.api.ContextSchema
import io.github.springconsole.api.EvalResult
import io.github.springconsole.binding.BeanBinder
import io.github.springconsole.binding.BoundBean
import io.github.springconsole.engine.EngineHolder
import io.github.springconsole.engine.EvalService
import io.github.springconsole.engine.KotlinReplEngine
import io.github.springconsole.engine.ReplBinding
import io.github.springconsole.engine.TransactionalExecutor
import io.github.springconsole.introspect.BeanIntrospector
import io.github.springconsole.introspect.ContextSchemaService
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.transaction.PlatformTransactionManager
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
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(ConsoleService::class.java)

    private val binder = BeanBinder(context, properties.includeInfrastructureBeans)

    private val boundBeans: List<BoundBean> by lazy { binder.boundBeans() }

    private val engineHolder = EngineHolder {
        val bindings = boundBeans.map { ReplBinding(it.replName, KotlinType(it.type.kotlin), it.instance) } +
            ReplBinding("context", KotlinType(ApplicationContext::class), context)
        KotlinReplEngine(
            bindings = bindings,
            baseClassLoader = context.classLoader ?: Thread.currentThread().contextClassLoader,
            defaultImports = properties.defaultImports,
        )
    }

    private val transactionalExecutor = TransactionalExecutor(
        context.beanFactory.getBeanProvider(PlatformTransactionManager::class.java).getIfUnique(),
    )

    private val evalService = EvalService(engineHolder, transactionalExecutor, properties.evalTimeoutMs)

    private val introspector = BeanIntrospector { boundBeans }

    private val schemaService = ContextSchemaService(context) { boundBeans }

    val rollbackSupported: Boolean
        get() = transactionalExecutor.rollbackSupported

    /**
     * Evaluates a Kotlin snippet. When [rollback] is null the configured
     * default applies (rollback on, per FR-2.1).
     */
    fun eval(code: String, rollback: Boolean? = null, timeoutMs: Long? = null): EvalResult =
        evalService.eval(code, rollback ?: properties.defaultRollback, timeoutMs)

    fun listBeans(packageFilter: String? = null, includeProxies: Boolean = false): List<BeanSummary> =
        introspector.listBeans(packageFilter, includeProxies)

    fun inspectBean(beanName: String): BeanDetails? = introspector.inspectBean(beanName)

    fun contextSchema(): ContextSchema = schemaService.schema()

    /** Pre-initializes the script compiler off the startup path. */
    fun warmUpAsync() {
        if (!properties.warmup) return
        Thread({
            try {
                engineHolder.warmUp()
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
