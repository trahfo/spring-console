package io.github.springconsole

import io.github.springconsole.api.BeanDetails
import io.github.springconsole.api.BeanSummary
import io.github.springconsole.api.ContextSchema
import io.github.springconsole.api.EvalResult

/**
 * Common operations interface supported by both the in-process [ConsoleService]
 * and the remote HTTP-based client ([io.github.springconsole.client.RemoteConsoleOperations]).
 *
 * Both human users (via JLine3 terminal) and AI agents (via MCP JSON-RPC) share
 * these operations for symmetric execution.
 */
interface ConsoleOperations {

    /**
     * The classloader for resolving project and framework types, if available.
     */
    val classLoader: ClassLoader?
        get() = null

    /**
     * Evaluates a Kotlin snippet against the Spring ApplicationContext.
     */
    fun eval(code: String): EvalResult = eval(code, null)

    /**
     * Evaluates a Kotlin snippet against the Spring ApplicationContext with an explicit timeout.
     */
    fun eval(code: String, timeoutMs: Long?): EvalResult

    /**
     * Lists beans registered in the context.
     */
    fun listBeans(): List<BeanSummary> = listBeans(null, false)

    fun listBeans(packageFilter: String?): List<BeanSummary> = listBeans(packageFilter, false)

    fun listBeans(packageFilter: String?, includeProxies: Boolean): List<BeanSummary>

    /**
     * Deeply inspects a bean by Spring name or REPL name.
     */
    fun inspectBean(beanName: String): BeanDetails?

    /**
     * Inspects an arbitrary class (such as a session variable's type).
     */
    fun inspectClass(name: String, type: Class<*>): BeanDetails

    /**
     * Resolves a simple class name (e.g. "Todo") to a runtime [Class].
     */
    fun resolveClassBySimpleName(simpleName: String): Class<*>?

    /**
     * Returns the domain schema of the application (entities, repositories, services).
     */
    fun contextSchema(): ContextSchema
}
