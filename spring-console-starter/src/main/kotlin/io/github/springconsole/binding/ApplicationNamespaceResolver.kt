package io.github.springconsole.binding

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfigurationPackages
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.type.classreading.CachingMetadataReaderFactory
import java.io.File

/**
 * Investigates project classes and packages across all project directories and packages
 * dynamically at runtime, without any compile-time dependencies on the host project.
 *
 * Imports classes into the REPL namespace as long as their simple names are unambiguous
 * (i.e. if there are two classes with the same simple name in different packages, neither
 * is imported unqualified to prevent collision).
 */
class ApplicationNamespaceResolver(
    private val context: ConfigurableApplicationContext,
) {
    private val log = LoggerFactory.getLogger(ApplicationNamespaceResolver::class.java)

    data class DiscoveredClass(
        val className: String,
        val simpleName: String,
        val packageName: String,
    )

    private val classLoader: ClassLoader =
        context.classLoader ?: Thread.currentThread().contextClassLoader ?: javaClass.classLoader

    private val discoveredClasses: List<DiscoveredClass> by lazy {
        scanProjectClasses()
    }

    private val classesBySimpleName: Map<String, List<DiscoveredClass>> by lazy {
        discoveredClasses.groupBy { it.simpleName }
    }

    /**
     * Fully-qualified class names for classes whose simple name is unique across the project.
     * E.g. `com.example.todo.Todo` when no other `Todo` class exists.
     */
    val unambiguousClassImports: List<String> by lazy {
        classesBySimpleName
            .filter { (_, classes) -> classes.size == 1 }
            .values
            .flatten()
            .map { it.className }
            .sorted()
    }

    /**
     * Wildcard package imports for all discovered project packages, e.g. `com.example.todo.*`.
     */
    val packageImports: List<String> by lazy {
        val packages = mutableSetOf<String>()

        // Base packages from Spring Boot
        try {
            if (AutoConfigurationPackages.has(context.beanFactory)) {
                packages.addAll(AutoConfigurationPackages.get(context.beanFactory))
            }
        } catch (ignored: Exception) {
        }

        // Packages from discovered classes
        discoveredClasses.map { it.packageName }.filter { it.isNotBlank() }.forEach { packages.add(it) }

        packages
            .filter { pkg -> !isFrameworkPackage(pkg) }
            .sorted()
            .map { "$it.*" }
    }

    /**
     * Resolves a simple class name (e.g. "Todo") to a runtime [Class],
     * preferring unambiguous classes or the first registered candidate.
     */
    fun resolveClassBySimpleName(simpleName: String): Class<*>? {
        val candidates = classesBySimpleName[simpleName] ?: return null
        val targetClassName = if (candidates.size == 1) {
            candidates[0].className
        } else {
            // If ambiguous, check if one of them matches a bean or entity
            candidates.firstOrNull { it.packageName.startsWith(primaryPackage()) }?.className
                ?: candidates[0].className
        }
        return try {
            Class.forName(targetClassName, false, classLoader)
        } catch (e: Exception) {
            log.debug("Failed to load discovered class: {}", targetClassName, e)
            null
        }
    }

    private fun primaryPackage(): String {
        return try {
            if (AutoConfigurationPackages.has(context.beanFactory)) {
                AutoConfigurationPackages.get(context.beanFactory).firstOrNull() ?: ""
            } else ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun scanProjectClasses(): List<DiscoveredClass> {
        val results = LinkedHashMap<String, DiscoveredClass>()

        // 1. Scan directory classpath entries (local compiled classes in Gradle/Maven/IDE output directories)
        scanClasspathDirectories(results)

        // 2. Scan base packages via Spring's resource resolver
        scanBasePackages(results)

        // 3. Scan bean definitions in the BeanFactory
        scanBeanDefinitions(results)

        return results.values.toList()
    }

    private fun scanClasspathDirectories(results: MutableMap<String, DiscoveredClass>) {
        val classPath = System.getProperty("java.class.path") ?: return
        val entries = classPath.split(File.pathSeparator).map { File(it) }

        for (entry in entries) {
            if (!entry.isDirectory) continue
            try {
                entry.walkTopDown()
                    .filter { it.isFile && it.extension == "class" && !it.name.contains('$') }
                    .forEach { file ->
                        val relative = file.relativeTo(entry).path
                        val className = relative.removeSuffix(".class")
                            .replace('/', '.')
                            .replace('\\', '.')
                        addClass(results, className)
                    }
            } catch (e: Exception) {
                log.debug("Could not scan directory: {}", entry, e)
            }
        }
    }

    private fun scanBasePackages(results: MutableMap<String, DiscoveredClass>) {
        val basePackages = mutableSetOf<String>()
        try {
            if (AutoConfigurationPackages.has(context.beanFactory)) {
                basePackages.addAll(AutoConfigurationPackages.get(context.beanFactory))
            }
        } catch (ignored: Exception) {
        }

        if (basePackages.isEmpty()) return

        val resolver = PathMatchingResourcePatternResolver(classLoader)
        val readerFactory = CachingMetadataReaderFactory(classLoader)

        for (basePkg in basePackages) {
            val path = basePkg.replace('.', '/')
            try {
                val resources = resolver.getResources("classpath*:$path/**/*.class")
                for (resource in resources) {
                    try {
                        val reader = readerFactory.getMetadataReader(resource)
                        val className = reader.classMetadata.className
                        if (!className.contains('$')) {
                            addClass(results, className)
                        }
                    } catch (ignored: Exception) {
                    }
                }
            } catch (e: Exception) {
                log.debug("Failed scanning package: {}", basePkg, e)
            }
        }
    }

    private fun scanBeanDefinitions(results: MutableMap<String, DiscoveredClass>) {
        try {
            for (beanName in context.beanFactory.beanDefinitionNames) {
                val def = try {
                    context.beanFactory.getBeanDefinition(beanName)
                } catch (ignored: Exception) {
                    null
                } ?: continue
                val className = def.beanClassName ?: continue
                if (!className.contains('$')) {
                    addClass(results, className)
                }
            }
        } catch (ignored: Exception) {
        }
    }

    private val basePackages: Set<String> by lazy {
        val pkgs = mutableSetOf<String>()
        try {
            if (AutoConfigurationPackages.has(context.beanFactory)) {
                pkgs.addAll(AutoConfigurationPackages.get(context.beanFactory))
            }
        } catch (ignored: Exception) {
        }
        pkgs
    }

    private fun addClass(results: MutableMap<String, DiscoveredClass>, className: String) {
        if (isFrameworkPackage(className)) return
        val simpleName = className.substringAfterLast('.')
        val packageName = className.substringBeforeLast('.', "")
        if (simpleName.isNotBlank() && !results.containsKey(className)) {
            results[className] = DiscoveredClass(
                className = className,
                simpleName = simpleName,
                packageName = packageName,
            )
        }
    }

    private fun isFrameworkPackage(name: String): Boolean {
        if (basePackages.any { name.startsWith(it) }) return false
        return name.startsWith("org.springframework.") ||
            name.startsWith("org.apache.") ||
            name.startsWith("org.hibernate.") ||
            name.startsWith("java.") ||
            name.startsWith("javax.") ||
            name.startsWith("jakarta.") ||
            name.startsWith("kotlin.") ||
            name.startsWith("kotlinx.") ||
            name.startsWith("com.sun.") ||
            name.startsWith("sun.") ||
            name.startsWith("jdk.") ||
            isStarterInternalPackage(name)
    }

    private fun isStarterInternalPackage(name: String): Boolean =
        name.startsWith("io.github.springconsole.engine.") ||
            name.startsWith("io.github.springconsole.binding.") ||
            name.startsWith("io.github.springconsole.introspect.") ||
            name.startsWith("io.github.springconsole.mcp.") ||
            name.startsWith("io.github.springconsole.reload.") ||
            name.startsWith("io.github.springconsole.repl.") ||
            name.startsWith("io.github.springconsole.autoconfigure.") ||
            name.startsWith("io.github.springconsole.api.") ||
            name.startsWith("io.github.springconsole.extensions.")
}
