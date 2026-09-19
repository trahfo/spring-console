package io.github.springconsole.client

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.springconsole.ConsoleOperations
import io.github.springconsole.api.BeanDetails
import io.github.springconsole.api.BeanSummary
import io.github.springconsole.api.ContextSchema
import io.github.springconsole.api.EvalResult
import io.github.springconsole.api.EvalStatus
import io.github.springconsole.api.ExceptionDetails
import io.github.springconsole.api.ReloadResult
import io.github.springconsole.api.ReloadStatus
import io.github.springconsole.introspect.BeanIntrospector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Implements [ConsoleOperations] by communicating with a running Spring Console
 * instance via its MCP HTTP JSON-RPC 2.0 endpoint.
 *
 * This allows a developer terminal (e.g. Tab 3) to attach directly to an application
 * already running on port 8080 (Tab 1) without spawning a second JVM or colliding on ports.
 */
class RemoteConsoleOperations(
    val mcpUrl: String = "http://127.0.0.1:8085/mcp",
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build(),
    private val mapper: ObjectMapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    },
) : ConsoleOperations {

    private val idGen = AtomicLong(1)
    private val introspector = BeanIntrospector { emptyList() }

    override val classLoader: ClassLoader?
        get() = Thread.currentThread().contextClassLoader ?: javaClass.classLoader

    /**
     * Checks whether the remote MCP endpoint is reachable and responsive.
     */
    fun ping(): Boolean = try {
        val responseNode = sendJsonRpc("ping", null, timeout = Duration.ofSeconds(2))
        responseNode != null && !responseNode.has("error")
    } catch (e: Exception) {
        false
    }

    override fun eval(code: String, timeoutMs: Long?): EvalResult {
        val args = mutableMapOf<String, Any>("code" to code)
        if (timeoutMs != null) {
            args["timeoutMs"] = timeoutMs
        }
        val resultNode = callTool("eval", args, timeout = Duration.ofMillis((timeoutMs ?: 10000) + 5000))
            ?: return EvalResult(
                status = EvalStatus.RUNTIME_EXCEPTION,
                result = "Failed to communicate with remote Spring Console at $mcpUrl",
                exception = ExceptionDetails(
                    type = "RemoteConsoleException",
                    message = "No response from $mcpUrl",
                    stackTrace = emptyList(),
                ),
            )

        return try {
            val structured = resultNode.path("structuredContent")
            if (structured.isObject) {
                mapper.treeToValue(structured, EvalResult::class.java)
            } else {
                val text = resultNode.path("content").get(0)?.path("text")?.asText() ?: ""
                mapper.readValue(text, EvalResult::class.java)
            }
        } catch (e: Exception) {
            EvalResult(
                status = EvalStatus.RUNTIME_EXCEPTION,
                result = "Failed to deserialize eval result: ${e.message}",
                exception = ExceptionDetails(
                    type = e.javaClass.simpleName,
                    message = e.message,
                    stackTrace = emptyList(),
                ),
            )
        }
    }

    override fun listBeans(packageFilter: String?, includeProxies: Boolean): List<BeanSummary> {
        val args = mutableMapOf<String, Any>("includeProxies" to includeProxies)
        if (packageFilter != null) {
            args["packageFilter"] = packageFilter
        }
        val resultNode = callTool("list_beans", args) ?: return emptyList()
        return try {
            val beansNode = resultNode.path("structuredContent").path("beans")
            if (beansNode.isArray) {
                val listType = mapper.typeFactory.constructCollectionType(List::class.java, BeanSummary::class.java)
                mapper.readValue(beansNode.toString(), listType)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun inspectBean(beanName: String): BeanDetails? {
        val resultNode = callTool("inspect_bean", mapOf("beanName" to beanName)) ?: return null
        if (resultNode.path("isError").asBoolean(false)) return null
        return try {
            val structured = resultNode.path("structuredContent")
            if (structured.isObject && !structured.isEmpty) {
                mapper.treeToValue(structured, BeanDetails::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun inspectClass(name: String, type: Class<*>): BeanDetails =
        introspector.inspectClass(name, type)

    override fun resolveClassBySimpleName(simpleName: String): Class<*>? {
        val cl = classLoader ?: return null
        // 1. Direct FQCN lookup
        try {
            return Class.forName(simpleName, false, cl)
        } catch (ignored: Exception) {}

        // 2. Discover package prefixes from beans
        val candidatePackages = try {
            listBeans().map { it.type.substringBeforeLast('.', "") }.filter { it.isNotBlank() }.distinct()
        } catch (e: Exception) {
            emptyList()
        }

        for (pkg in candidatePackages) {
            try {
                return Class.forName("$pkg.$simpleName", false, cl)
            } catch (ignored: Exception) {}
        }
        return null
    }

    override fun contextSchema(): ContextSchema {
        val resultNode = callTool("get_context_schema", emptyMap<String, Any>())
            ?: return ContextSchema(emptyList(), emptyList(), emptyList())
        return try {
            val structured = resultNode.path("structuredContent")
            mapper.treeToValue(structured, ContextSchema::class.java)
        } catch (e: Exception) {
            ContextSchema(emptyList(), emptyList(), emptyList())
        }
    }

    fun reload(recompile: Boolean = true): ReloadResult {
        val resultNode = callTool("reload", mapOf("recompile" to recompile), timeout = Duration.ofMinutes(2))
            ?: return ReloadResult(
                status = ReloadStatus.RESTART_FAILED,
                message = "Failed to communicate with remote Spring Console during reload at $mcpUrl",
            )
        return try {
            val structured = resultNode.path("structuredContent")
            mapper.treeToValue(structured, ReloadResult::class.java)
        } catch (e: Exception) {
            ReloadResult(
                status = ReloadStatus.RESTART_FAILED,
                message = "Failed to deserialize reload result: ${e.message}",
            )
        }
    }

    private fun callTool(name: String, arguments: Map<String, Any>, timeout: Duration = Duration.ofSeconds(30)): JsonNode? {
        val params = mapOf("name" to name, "arguments" to arguments)
        val response = sendJsonRpc("tools/call", params, timeout) ?: return null
        return response.path("result")
    }

    private fun sendJsonRpc(method: String, params: Any?, timeout: Duration = Duration.ofSeconds(10)): JsonNode? {
        val id = idGen.getAndIncrement()
        val requestMap = mutableMapOf<String, Any?>(
            "jsonrpc" to "2.0",
            "id" to id,
            "method" to method,
        )
        if (params != null) {
            requestMap["params"] = params
        }
        val requestBody = mapper.writeValueAsString(requestMap)
        val request = HttpRequest.newBuilder()
            .uri(URI.create(mcpUrl))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(timeout)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            return null
        }

        if (response.statusCode() !in 200..299) {
            return null
        }
        return mapper.readTree(response.body())
    }
}
