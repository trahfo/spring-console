package io.github.springconsole.reload

import java.net.URL
import java.net.URLClassLoader
import java.util.Collections
import java.util.Enumeration

/**
 * Child-first classloader over the project's compiled-classes directories
 * (the Spring Boot DevTools RestartClassLoader pattern, FR-3.3).
 *
 * Application classes — anything found under the given directories — are
 * loaded fresh from disk so recompiled bytes win; everything else (framework
 * jars, this library, the JDK) is delegated to the stable parent, keeping the
 * console runtime and its singletons shared across restarts.
 */
class RestartClassLoader(
    urls: Array<URL>,
    parent: ClassLoader,
) : URLClassLoader("spring-console-restart", urls, parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { loaded ->
                if (resolve) resolveClass(loaded)
                return loaded
            }
            val childFirst = try {
                findClass(name)
            } catch (e: ClassNotFoundException) {
                null
            }
            if (childFirst != null) {
                if (resolve) resolveClass(childFirst)
                return childFirst
            }
            return super.loadClass(name, resolve)
        }
    }

    override fun getResource(name: String): URL? = findResource(name) ?: super.getResource(name)

    override fun getResources(name: String): Enumeration<URL> {
        val own = findResources(name).toList()
        val inherited = parent.getResources(name).toList().filterNot { it in own }
        return Collections.enumeration(own + inherited)
    }

    companion object {
        init {
            registerAsParallelCapable()
        }
    }
}
