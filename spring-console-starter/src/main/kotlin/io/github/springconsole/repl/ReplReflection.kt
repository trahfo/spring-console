package io.github.springconsole.repl

import java.beans.Introspector
import java.lang.reflect.Method

/**
 * Reflection helpers shared by the REPL's completion and rendering layers.
 *
 * Both need the same answers to the same three questions — "is this a
 * JavaBeans-style accessor?", "what property does it expose?", "is this a
 * Hibernate proxy?" — so the logic lives here once instead of drifting apart in
 * two private copies.
 *
 * Nothing in here throws for unusual types: every helper is a pure predicate
 * or a string transform.
 */
internal object ReplReflection {

    /**
     * Methods inherited from `java.lang.Object` that are never useful
     * completions or worth listing in a rendered object view.
     */
    val OBJECT_METHODS: Set<String> = setOf(
        "getClass", "hashCode", "equals", "toString", "notify", "notifyAll", "wait", "clone", "finalize",
    )

    /**
     * True when [method] is a readable, no-argument accessor, i.e. `getX()` or
     * a boolean `isX()`. Deliberately *not* based on
     * `java.beans.Introspector`, because the introspector does not derive
     * properties from interface methods — and the console completes members of
     * `ApplicationContext`, an interface.
     */
    fun isGetter(method: Method): Boolean = when {
        method.parameterCount != 0 -> false
        method.name.startsWith("get") && method.name.length > 3 -> true
        method.name.startsWith("is") && method.name.length > 2 ->
            method.returnType == java.lang.Boolean.TYPE || method.returnType == java.lang.Boolean::class.java
        else -> false
    }

    /** `getTitle` → `title`, `isDone` → `done`, `getURL` → `URL`. */
    fun propertyName(method: Method): String {
        val raw = when {
            method.name.startsWith("get") -> method.name.substring(3)
            method.name.startsWith("is") -> method.name.substring(2)
            else -> method.name
        }
        return Introspector.decapitalize(raw)
    }

    /**
     * Detects Hibernate/JPA lazy proxies *by name* so the console never calls
     * `toString()`/getters on an uninitialized proxy (which would trigger lazy
     * loading or throw `LazyInitializationException`).
     */
    fun isHibernateProxy(type: Class<*>): Boolean =
        type.name.contains("HibernateProxy") ||
            type.interfaces.any { it.name == "org.hibernate.proxy.HibernateProxy" }
}
