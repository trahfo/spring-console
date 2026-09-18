package io.github.springconsole.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.springconsole.ConsoleService
import io.github.springconsole.api.ReloadResult
import io.github.springconsole.api.ReloadStatus

/**
 * The console's MCP tool surface: definitions (name, description, JSON input
 * schema) and dispatch onto the current [ConsoleService].
 *
 * Suppliers indirect through the runtime so the tool surface survives context
 * reloads: after a restart the same registry serves the new context.
 */
class ConsoleTools(
    private val mapper: ObjectMapper,
    private val consoleSupplier: () -> ConsoleService?,
    private val reloadHandler: ((recompile: Boolean) -> ReloadResult)? = null,
) {

    data class ToolDefinition(val name: String, val description: String, val inputSchema: ObjectNode)

    data class ToolResult(val payload: Any, val isError: Boolean = false)

    fun definitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "eval",
            description = "Execute a Kotlin snippet against the running Spring ApplicationContext. " +
                "Beans are pre-bound as typed variables (see list_beans for their REPL names); `context` " +
                "holds the ApplicationContext. Snippets share state within a session. By default the " +
                "snippet runs inside a transaction that is always rolled back, so database mutations " +
                "leave no trace; pass rollback=false to persist changes.",
            inputSchema = schema {
                property("code", "string", "Kotlin code to evaluate", required = true)
                property("rollback", "boolean", "Roll back all transactional changes after execution (default true)")
                property("timeoutMs", "integer", "Max user-code execution time in ms (default 5000); compilation is not counted")
            },
        ),
        ToolDefinition(
            name = "list_beans",
            description = "List beans registered in the ApplicationContext with their resolved (unproxied) " +
                "types and the variable names under which they are bound in eval snippets.",
            inputSchema = schema {
                property("packageFilter", "string", "Only include beans whose type starts with this package prefix")
                property("includeProxies", "boolean", "Report runtime proxy classes instead of resolved target types (default false)")
            },
        ),
        ToolDefinition(
            name = "inspect_bean",
            description = "Inspect one bean: unproxied type, interfaces, public methods with signatures, " +
                "and properties. Accepts the Spring bean name or the REPL variable name.",
            inputSchema = schema {
                property("beanName", "string", "Spring bean name or REPL variable name", required = true)
            },
        ),
        ToolDefinition(
            name = "reload",
            description = "Recompile modified sources with the project's build tool, restart the Spring " +
                "ApplicationContext inside the running JVM, and re-bind the console. Use after editing " +
                "source files to make changes live without restarting the process. REPL snippet state is reset.",
            inputSchema = schema {
                property("recompile", "boolean", "Run the incremental build before restarting (default true)")
            },
        ),
        ToolDefinition(
            name = "get_context_schema",
            description = "Dump the application's domain shape: JPA entities with attributes, Spring Data " +
                "repositories with query methods and domain types, and @Service beans.",
            inputSchema = schema { },
        ),
    )

    fun call(name: String, arguments: JsonNode?): ToolResult {
        if (name == "reload") {
            val recompile = arguments?.path("recompile")?.takeIf { it.isBoolean }?.asBoolean() ?: true
            val handler = reloadHandler
                ?: return ToolResult(
                    ReloadResult(ReloadStatus.NOT_SUPPORTED, "Reload is not available in this runtime."),
                    isError = false,
                )
            return ToolResult(handler(recompile))
        }

        val console = consoleSupplier()
            ?: return ToolResult(
                mapOf("error" to "The application context is not available (it may be restarting). Retry shortly."),
                isError = true,
            )

        return when (name) {
            "eval" -> {
                val code = arguments?.path("code")?.takeIf { it.isTextual }?.asText()
                    ?: return ToolResult(mapOf("error" to "Missing required argument: code"), isError = true)
                val rollback = arguments.path("rollback").takeIf { it.isBoolean }?.asBoolean()
                val timeoutMs = arguments.path("timeoutMs").takeIf { it.isIntegralNumber }?.asLong()
                ToolResult(console.eval(code, rollback, timeoutMs))
            }
            "list_beans" -> {
                val packageFilter = arguments?.path("packageFilter")?.takeIf { it.isTextual }?.asText()
                val includeProxies = arguments?.path("includeProxies")?.takeIf { it.isBoolean }?.asBoolean() ?: false
                ToolResult(mapOf("beans" to console.listBeans(packageFilter, includeProxies)))
            }
            "inspect_bean" -> {
                val beanName = arguments?.path("beanName")?.takeIf { it.isTextual }?.asText()
                    ?: return ToolResult(mapOf("error" to "Missing required argument: beanName"), isError = true)
                val details = console.inspectBean(beanName)
                if (details != null) {
                    ToolResult(details)
                } else {
                    ToolResult(
                        mapOf("error" to "No bean named '$beanName'. Use list_beans to see available beans."),
                        isError = true,
                    )
                }
            }
            "get_context_schema" -> ToolResult(console.contextSchema())
            else -> throw UnknownToolException(name)
        }
    }

    class UnknownToolException(val toolName: String) : RuntimeException("Unknown tool: $toolName")

    private class SchemaBuilder(private val mapper: ObjectMapper) {
        val properties: ObjectNode = mapper.createObjectNode()
        val required = mutableListOf<String>()

        fun property(name: String, type: String, description: String, required: Boolean = false) {
            properties.set<ObjectNode>(
                name,
                mapper.createObjectNode().apply {
                    put("type", type)
                    put("description", description)
                },
            )
            if (required) this.required += name
        }
    }

    private fun schema(block: SchemaBuilder.() -> Unit): ObjectNode {
        val builder = SchemaBuilder(mapper).apply(block)
        return mapper.createObjectNode().apply {
            put("type", "object")
            set<ObjectNode>("properties", builder.properties)
            if (builder.required.isNotEmpty()) {
                set<JsonNode>("required", mapper.valueToTree(builder.required))
            }
        }
    }
}
