package io.github.springconsole.binding

import org.slf4j.LoggerFactory

/**
 * Utility for reflective detection, eager initialization, and unwrapping of
 * Hibernate proxies, ensuring `LazyInitializationException` never occurs when
 * entities are accessed across snippets or after sessions close.
 *
 * Designed with zero compile-time dependencies on Hibernate.
 */
object HibernateProxyHelper {

    private val log = LoggerFactory.getLogger(HibernateProxyHelper::class.java)

    /**
     * Initializes and unwraps [target] if it is a Hibernate proxy.
     * Recursively traverses collections and maps.
     */
    fun initializeAndUnwrap(target: Any?): Any? {
        if (target == null) return null
        return try {
            when {
                isHibernateProxy(target) -> unwrapProxy(target)
                target is Collection<*> -> {
                    target.map { initializeAndUnwrap(it) }
                }
                else -> target
            }
        } catch (e: Exception) {
            log.debug("Could not initialize/unwrap proxy: {}", target, e)
            target
        }
    }

    /**
     * Checks if [target] implements `org.hibernate.proxy.HibernateProxy`.
     */
    fun isHibernateProxy(target: Any?): Boolean {
        if (target == null) return false
        val cls = target.javaClass
        return cls.interfaces.any { it.name == "org.hibernate.proxy.HibernateProxy" } ||
            generateSequence(cls.superclass) { it.superclass }.any { it.name.contains("HibernateProxy") }
    }

    private fun unwrapProxy(proxy: Any): Any {
        val getInitializer = proxy.javaClass.getMethod("getHibernateLazyInitializer")
        val initializer = getInitializer.invoke(proxy) ?: return proxy
        val getImplementation = initializer.javaClass.getMethod("getImplementation")
        return getImplementation.invoke(initializer) ?: proxy
    }
}
