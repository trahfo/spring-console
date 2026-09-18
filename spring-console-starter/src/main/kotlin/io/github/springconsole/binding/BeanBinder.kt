package io.github.springconsole.binding

import io.github.springconsole.engine.ReplBinding
import org.slf4j.LoggerFactory
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.util.ClassUtils
import kotlin.script.experimental.api.KotlinType

/**
 * One application bean bound into the REPL scope, with the type the script
 * compiler should see (proxies unwrapped, FR-1.2) while the injected value
 * remains the live (possibly proxied) instance so AOP semantics such as
 * `@Transactional` still apply inside snippets.
 */
data class BoundBean(
    val beanName: String,
    val replName: String,
    val type: Class<*>,
    val instance: Any,
    val proxied: Boolean,
    val scope: String,
)

/**
 * Scans the [ApplicationContext] and produces the REPL bindings (FR-1.1).
 *
 * Only instantiated singletons are bound: prototype and lazy beans are skipped
 * so that building the console scope never triggers bean creation side
 * effects. Framework-internal beans (those with fully-qualified-class-style
 * names under `org.springframework`) are excluded by default.
 */
class BeanBinder(
    private val context: ConfigurableApplicationContext,
    private val includeInfrastructure: Boolean = false,
) {
    private val log = LoggerFactory.getLogger(BeanBinder::class.java)

    /** Bean-name prefixes that are never useful inside the console. */
    private val excludedNamePrefixes = listOf(
        "org.springframework.",
        "spring.internal.",
    )

    fun boundBeans(): List<BoundBean> {
        val beanFactory = context.beanFactory
        val taken = mutableSetOf("context", "beanFactory")
        val bound = mutableListOf<BoundBean>()

        for (name in beanFactory.singletonNames.sorted()) {
            if (!includeInfrastructure && excludedNamePrefixes.any { name.startsWith(it) }) continue
            // getBean (not getSingleton) so FactoryBeans yield their product,
            // e.g. a Spring Data factory bean yields the repository proxy.
            val instance = try {
                beanFactory.getBean(name)
            } catch (e: Exception) {
                log.debug("Skipping bean '{}': {}", name, e.message)
                continue
            }
            // The console's own beans (properties, bootstrap listener, …) live
            // directly in this package; user code is always elsewhere.
            if (instance.javaClass.`package`?.name == "io.github.springconsole") continue

            val (type, proxied) = resolveBindingType(instance)
            if (!isScriptReferencable(type)) {
                log.debug("Skipping bean '{}': type {} cannot be referenced from a script", name, type.name)
                continue
            }

            val replName = NameSanitizer.sanitizeUnique(name, taken)
            taken += replName
            bound += BoundBean(
                beanName = name,
                replName = replName,
                type = type,
                instance = instance,
                proxied = proxied,
                scope = "singleton",
            )
        }
        return bound
    }

    /** All REPL bindings: the bound beans plus the `context` convenience binding. */
    fun bindings(): List<ReplBinding> {
        val beanBindings = boundBeans().map {
            ReplBinding(it.replName, KotlinType(it.type.kotlin), it.instance)
        }
        return beanBindings + ReplBinding("context", KotlinType(ApplicationContext::class), context)
    }

    /**
     * Resolves the type the script compiler should use for a bean (FR-1.2).
     *
     * - JDK dynamic proxies only satisfy their interfaces, so the most
     *   specific non-framework interface is used (e.g. `TodoRepository`
     *   rather than the unassignable `SimpleJpaRepository` target class).
     * - CGLIB proxies are subclasses of the target, so the unwrapped user
     *   class is both accurate and assignable.
     */
    private fun resolveBindingType(instance: Any): Pair<Class<*>, Boolean> {
        val proxied = AopUtils.isAopProxy(instance)
        // Any JDK dynamic proxy (Spring AOP or otherwise) only satisfies its
        // interfaces, so the target class would not be assignable.
        if (java.lang.reflect.Proxy.isProxyClass(instance.javaClass)) {
            val interfaces = instance.javaClass.interfaces
            val preferred = interfaces.firstOrNull { !isFrameworkType(it) }
                ?: interfaces.firstOrNull()
                ?: Any::class.java
            return preferred to true
        }
        val target = try {
            AopUtils.getTargetClass(instance)
        } catch (e: Exception) {
            instance.javaClass
        }
        return ClassUtils.getUserClass(target) to proxied
    }

    /** Scripts can only name public, non-synthetic, non-local top-level or nested types. */
    private fun isScriptReferencable(type: Class<*>): Boolean =
        !type.isSynthetic &&
            !type.isAnonymousClass &&
            !type.isLocalClass &&
            java.lang.reflect.Modifier.isPublic(type.modifiers)

    private fun isFrameworkType(type: Class<*>): Boolean =
        listOf("org.springframework.", "java.", "javax.", "jakarta.", "kotlin.")
            .any { type.name.startsWith(it) }

    private val ConfigurableListableBeanFactory.singletonNames: List<String>
        get() = beanDefinitionNames.filter { name ->
            try {
                containsSingleton(name)
            } catch (e: Exception) {
                false
            }
        }
}
