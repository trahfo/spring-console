package io.github.springconsole.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/** JSON-RPC 2.0 protocol constants and helpers for the MCP endpoint. */
object JsonRpc {
    const val VERSION = "2.0"

    // Standard error codes
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    fun response(mapper: ObjectMapper, id: JsonNode?, result: JsonNode): ObjectNode =
        mapper.createObjectNode().apply {
            put("jsonrpc", VERSION)
            set<JsonNode>("id", id ?: mapper.nullNode())
            set<JsonNode>("result", result)
        }

    fun error(mapper: ObjectMapper, id: JsonNode?, code: Int, message: String): ObjectNode =
        mapper.createObjectNode().apply {
            put("jsonrpc", VERSION)
            set<JsonNode>("id", id ?: mapper.nullNode())
            set<JsonNode>(
                "error",
                mapper.createObjectNode().apply {
                    put("code", code)
                    put("message", message)
                },
            )
        }
}

/** A parsed JSON-RPC request or notification. */
data class JsonRpcMessage(
    val id: JsonNode?,
    val method: String,
    val params: JsonNode?,
) {
    /** Notifications carry no id and expect no response. */
    val isNotification: Boolean
        get() = id == null || id.isNull
}
