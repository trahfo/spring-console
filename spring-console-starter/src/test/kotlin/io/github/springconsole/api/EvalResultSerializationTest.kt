package io.github.springconsole.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the contract that the raw evaluation value — which exists purely for
 * colorized terminal rendering — never leaks into the JSON payload returned to
 * MCP agents. Agents must keep receiving the stable, serializable shape
 * documented in the spec (`status`, `result`, `printedOutput`, …).
 */
class EvalResultSerializationTest {

    private val mapper = jacksonObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)

    @Test
    fun `raw object values are excluded from the JSON payload`() {
        val json = mapper.writeValueAsString(
            EvalResult(
                status = EvalStatus.SUCCESS,
                result = "TodoRow(id=1, title=milk, completed=false)",
                rawValue = mapOf("leakedMarker" to "must-not-appear"),
            ),
        )

        assertFalse(json.contains("\"rawValue\""), json)
        assertFalse(json.contains("leakedMarker"), "raw value leaked into: $json")
        assertTrue(json.contains("\"result\":\"TodoRow(id=1, title=milk, completed=false)\""), json)
    }

    @Test
    fun `unit raw values are excluded as well`() {
        val json = mapper.writeValueAsString(EvalResult(status = EvalStatus.SUCCESS, rawValue = Unit))

        assertFalse(json.contains("rawValue"), json)
        assertTrue(json.contains("\"status\":\"SUCCESS\""), json)
    }

    @Test
    fun `structured diagnostics still serialize`() {
        val json = mapper.writeValueAsString(
            EvalResult(
                status = EvalStatus.COMPILATION_ERROR,
                printedOutput = "out",
                executionTimeMs = 5,
                compilationErrors = listOf(CompilationError(line = 1, column = 2, message = "Unresolved reference")),
            ),
        )

        assertTrue(json.contains("\"status\":\"COMPILATION_ERROR\""), json)
        assertTrue(json.contains("\"printedOutput\":\"out\""), json)
        assertTrue(json.contains("\"line\":1"), json)
        assertTrue(json.contains("\"column\":2"), json)
    }
}
