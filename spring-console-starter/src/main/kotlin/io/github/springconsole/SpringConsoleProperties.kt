package io.github.springconsole

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for the console, bound from the `spring-console.*` namespace.
 */
@ConfigurationProperties("spring-console")
class SpringConsoleProperties {
    /** Master switch for the whole console. */
    var enabled: Boolean = true

    /**
     * Default for `eval`'s rollback flag when the caller does not specify one
     * (FR-2.1). Applies to MCP and REPL alike.
     */
    var defaultRollback: Boolean = true

    /** Default evaluation timeout, overridable per `eval` call. */
    var evalTimeoutMs: Long = 5_000

    /**
     * Compile a trivial snippet in a background thread at startup so the first
     * real evaluation does not pay the script-compiler initialization cost.
     */
    var warmup: Boolean = true

    /** Bind Spring's own internal beans (names under `org.springframework.`) too. */
    var includeInfrastructureBeans: Boolean = false

    /** Imports added to every snippet, e.g. `com.example.domain.*`. */
    var defaultImports: List<String> = emptyList()

    val mcp = Mcp()
    val repl = Repl()
    val reload = Reload()

    class Mcp {
        /** Expose the MCP server over HTTP. */
        var enabled: Boolean = true

        /**
         * Interface to bind. Local-only by default: the console executes
         * arbitrary code, never expose it beyond the machine you trust.
         */
        var host: String = "127.0.0.1"

        var port: Int = 8085

        /** URL path of the JSON-RPC endpoint (streamable HTTP transport). */
        var path: String = "/mcp"

        /**
         * Serve MCP over stdin/stdout instead of HTTP. Takes over the
         * process's standard streams, so the terminal REPL is disabled and
         * console logging should be routed to stderr or a file.
         */
        var stdio: Boolean = false
    }

    class Repl {
        /**
         * Start the interactive terminal REPL when the process has a usable
         * terminal attached. Never starts under stdio MCP mode.
         */
        var enabled: Boolean = true
    }

    class Reload {
        /** Allow `reload` to recompile and restart the application context. */
        var enabled: Boolean = true

        /**
         * Build command used for incremental recompilation. Auto-detected when
         * empty: a Gradle wrapper resolves to `gradlew classes`, a Maven
         * wrapper to `mvnw compile`.
         */
        var command: List<String> = emptyList()

        /** Root of the project's build; defaults to the process working directory. */
        var projectDir: String = ""

        /** Maximum time to wait for the build tool. */
        var compileTimeoutMs: Long = 120_000

        /** Maximum time to wait for the restarted context to come up. */
        var restartTimeoutMs: Long = 60_000
    }
}
