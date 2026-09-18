package io.github.springconsole.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory

/**
 * Transport-agnostic MCP protocol handler (JSON-RPC 2.0): implements
 * `initialize`, `ping`, `tools/list`, and `tools/call` against the console's
 * tool registry. Stateless per request, so it serves HTTP and stdio alike and
 * survives application-context reloads.
 */
class McpServer(
    private val mapper: ObjectMapper,
    private val tools: ConsoleTools,
    private val serverVersion: String = SERVER_VERSION,
) {
    private val log = LoggerFactory.getLogger(McpServer::class.java)

    companion object {
        const val SERVER_NAME = "spring-console"
        val SERVER_VERSION: String = McpServer::class.java.`package`?.implementationVersion ?: "0.1.0"

        /** Protocol revisions this server understands, newest first. */
        val SUPPORTED_PROTOCOL_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")
    }

    /**
     * Handles one raw JSON-RPC message.
     *
     * @return the serialized response, or null for notifications.
     */
    fun handle(body: String): String? {
        val message = try {
            parse(body)
        } catch (e: Exception) {
            return JsonRpc.error(mapper, null, JsonRpc.PARSE_ERROR, "Parse error: ${e.message}").toString()
        } ?: return JsonRpc.error(
            mapper,
            null,
            JsonRpc.INVALID_REQUEST,
            "Invalid request: expected a JSON-RPC 2.0 object with a method",
        ).toString()

        if (message.isNotification) {
            log.debug("MCP notification: {}", message.method)
            return null
        }

        val response = try {
            dispatch(message)
        } catch (e: ConsoleTools.UnknownToolException) {
            JsonRpc.error(mapper, message.id, JsonRpc.INVALID_PARAMS, "Unknown tool: ${e.toolName}")
        } catch (e: Exception) {
            log.error("MCP request failed: {}", message.method, e)
            JsonRpc.error(mapper, message.id, JsonRpc.INTERNAL_ERROR, "${e.javaClass.simpleName}: ${e.message}")
        }
        return response.toString()
    }

    private fun parse(body: String): JsonRpcMessage? {
        val node = mapper.readTree(body)
        if (!node.isObject || !node.path("method").isTextual) return null
        return JsonRpcMessage(
            id = node.get("id"),
            method = node.path("method").asText(),
            params = node.get("params"),
        )
    }

    private fun dispatch(message: JsonRpcMessage): ObjectNode = when (message.method) {
        "initialize" -> JsonRpc.response(mapper, message.id, initializeResult(message.params))
        "ping" -> JsonRpc.response(mapper, message.id, mapper.createObjectNode())
        "tools/list" -> JsonRpc.response(mapper, message.id, toolsListResult())
        "tools/call" -> JsonRpc.response(mapper, message.id, toolsCallResult(message.params))
        else -> JsonRpc.error(mapper, message.id, JsonRpc.METHOD_NOT_FOUND, "Method not found: ${message.method}")
    }

    private fun initializeResult(params: JsonNode?): ObjectNode {
        val requested = params?.path("protocolVersion")?.takeIf { it.isTextual }?.asText()
        val negotiated = if (requested in SUPPORTED_PROTOCOL_VERSIONS) requested else SUPPORTED_PROTOCOL_VERSIONS.first()
        return mapper.createObjectNode().apply {
            put("protocolVersion", negotiated)
            set<ObjectNode>(
                "capabilities",
                mapper.createObjectNode().apply {
                    set<ObjectNode>("tools", mapper.createObjectNode().put("listChanged", false))
                },
            )
            set<ObjectNode>(
                "serverInfo",
                mapper.createObjectNode().apply {
                    put("name", SERVER_NAME)
                    put("version", serverVersion)
                },
            )
            put(
                "instructions",
                "Interactive Kotlin console for a running Spring Boot application. " +
                    "Use list_beans/inspect_bean/get_context_schema to discover the runtime, eval to execute Kotlin " +
                    "snippets against live beans (rolled back by default — pass rollback=false to persist), and " +
                    "reload to recompile edited sources and hot-restart the context without killing the JVM.",
            )
        }
    }

    private fun toolsListResult(): ObjectNode = mapper.createObjectNode().apply {
        set<JsonNode>(
            "tools",
            mapper.valueToTree(
                tools.definitions().map {
                    mapOf("name" to it.name, "description" to it.description, "inputSchema" to it.inputSchema)
                },
            ),
        )
    }

    private fun toolsCallResult(params: JsonNode?): ObjectNode {
        val name = params?.path("name")?.takeIf { it.isTextual }?.asText()
            ?: throw IllegalArgumentException("tools/call requires a tool name")
        val result = tools.call(name, params.get("arguments"))
        val payloadJson: JsonNode = mapper.valueToTree(result.payload)
        return mapper.createObjectNode().apply {
            set<JsonNode>(
                "content",
                mapper.createArrayNode().add(
                    mapper.createObjectNode().apply {
                        put("type", "text")
                        put("text", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payloadJson))
                    },
                ),
            )
            if (payloadJson.isObject) {
                set<JsonNode>("structuredContent", payloadJson)
            }
            put("isError", result.isError)
        }
    }
}
