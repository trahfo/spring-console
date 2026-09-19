package io.github.springconsole.repl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Methods used to probe [ReplReflection.isGetter] / [ReplReflection.propertyName]. */
@Suppress("unused")
private class ReflectionSample {
    fun getTitle(): String = "t"
    fun getURL(): String = "u"
    fun isDone(): Boolean = true
    /** Boxed Boolean (nullable in Kotlin) exercises the `java.lang.Boolean` branch. */
    fun isActive(): Boolean? = true
    fun isName(): String = "not a boolean getter"
    fun title(): String = "record-style accessor"
    fun lookup(id: Long): String = "has a parameter"
}

/**
 * Unit tests for [ReplReflection], the shared reflection helpers used by both
 * the completer and the result renderer. They answer "is this an accessor?"
 * and "what is the property called?", and the Hibernate-proxy check that keeps
 * rendering from triggering lazy loading.
 */
class ReplReflectionTest {

    private fun method(name: String, parameterCount: Int = 0) =
        ReflectionSample::class.java.methods.first { it.name == name && it.parameterCount == parameterCount }

    @Test
    fun `recognizes getter and boolean is-getter accessors`() {
        assertTrue(ReplReflection.isGetter(method("getTitle")))
        assertTrue(ReplReflection.isGetter(method("getURL")))
        assertTrue(ReplReflection.isGetter(method("isDone")))
        assertTrue(ReplReflection.isGetter(method("isActive")), "boxed Boolean isX is a getter")
    }

    @Test
    fun `rejects non getters`() {
        assertFalse(ReplReflection.isGetter(method("isName")), "non-boolean isX is not a getter")
        assertFalse(ReplReflection.isGetter(method("title")), "record-style accessor is not a JavaBeans getter")
        assertFalse(ReplReflection.isGetter(method("lookup", parameterCount = 1)), "parameterized methods are not getters")
        assertFalse(ReplReflection.isGetter(method("toString")))
    }

    @Test
    fun `derives property names the way JavaBeans does`() {
        assertEquals("title", ReplReflection.propertyName(method("getTitle")))
        assertEquals("done", ReplReflection.propertyName(method("isDone")))
        assertEquals("active", ReplReflection.propertyName(method("isActive")))
        // A leading run of capitals is preserved, matching Introspector.decapitalize.
        assertEquals("URL", ReplReflection.propertyName(method("getURL")))
    }

    @Test
    fun `object methods are never offered`() {
        assertTrue(ReplReflection.OBJECT_METHODS.containsAll(listOf("getClass", "hashCode", "equals", "toString")))
    }

    @Test
    fun `detects hibernate proxies by name or interface`() {
        assertTrue(ReplReflection.isHibernateProxy(FakeHibernateProxy::class.java))
        assertFalse(ReplReflection.isHibernateProxy(String::class.java))
        assertFalse(ReplReflection.isHibernateProxy(ReflectionSample::class.java))
    }

    @Test
    fun `an interface getter is recognized`() {
        // The console completes ApplicationContext members, and the JavaBeans
        // introspector would return no properties for an interface.
        val environmentGetter = org.springframework.context.ApplicationContext::class.java.methods
            .first { it.name == "getEnvironment" }

        assertTrue(ReplReflection.isGetter(environmentGetter))
        assertEquals("environment", ReplReflection.propertyName(environmentGetter))
    }
}
