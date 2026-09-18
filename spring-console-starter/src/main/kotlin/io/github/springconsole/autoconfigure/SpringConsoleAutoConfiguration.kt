package io.github.springconsole.autoconfigure

import io.github.springconsole.ConsoleRuntime
import io.github.springconsole.ConsoleService
import io.github.springconsole.SpringConsoleProperties
import io.github.springconsole.reload.RestartContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.event.ContextClosedEvent
import java.io.File

/**
 * Wires the console into any Spring Boot application that has this starter on
 * its classpath. Disable entirely with `spring-console.enabled=false`.
 */
@AutoConfiguration
@EnableConfigurationProperties(SpringConsoleProperties::class)
@ConditionalOnProperty(prefix = "spring-console", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class SpringConsoleAutoConfiguration {

    @Bean
    fun springConsoleBootstrap(properties: SpringConsoleProperties): ConsoleBootstrap = ConsoleBootstrap(properties)
}

/**
 * Attaches this context to the process-wide [ConsoleRuntime] once the
 * application is fully ready (so all singletons exist for binding), and
 * detaches it on close. Interface availability tracks context readiness,
 * satisfying the < 2.5 s cold-boot NFR: transports start in milliseconds and
 * the script engine warms up asynchronously.
 */
class ConsoleBootstrap(private val properties: SpringConsoleProperties) : ApplicationListener<ApplicationEvent> {

    private val log = LoggerFactory.getLogger(ConsoleBootstrap::class.java)

    override fun onApplicationEvent(event: ApplicationEvent) {
        when (event) {
            is ApplicationReadyEvent -> attach(event)
            is ContextClosedEvent -> ConsoleRuntime.detach(event.applicationContext)
        }
    }

    private fun attach(event: ApplicationReadyEvent) {
        val context = event.applicationContext as? ConfigurableApplicationContext ?: return
        try {
            ConsoleRuntime.attach(ConsoleService(context, properties), restartContext(event))
        } catch (e: Exception) {
            log.error("Spring Console failed to start; the application is unaffected", e)
        }
    }

    /**
     * Reload support needs a real `main` to re-run and directory classpath
     * entries to re-read compiled classes from; both are absent in tests and
     * some launchers, where reload degrades to NOT_SUPPORTED.
     */
    private fun restartContext(event: ApplicationReadyEvent): RestartContext? {
        val mainClass = event.springApplication.mainApplicationClass ?: return null
        val hasMain = try {
            mainClass.getDeclaredMethod("main", Array<String>::class.java)
            true
        } catch (e: NoSuchMethodException) {
            false
        }
        if (!hasMain) return null

        val classpathDirs = System.getProperty("java.class.path", "")
            .split(File.pathSeparator)
            .map(::File)
            .filter { it.isDirectory }
        if (classpathDirs.isEmpty()) return null

        return RestartContext(
            mainClassName = mainClass.name,
            args = event.args,
            classpathDirs = classpathDirs,
            parentLoader = mainClass.classLoader,
        )
    }
}
