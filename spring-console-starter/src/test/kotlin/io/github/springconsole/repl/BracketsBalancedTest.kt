package io.github.springconsole.repl

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the multi-line continuation heuristic used by [ConsoleRepl]: a
 * snippet is only "complete" when its brackets and literals are balanced.
 */
class BracketsBalancedTest {

    @Test
    fun `plain expressions are complete`() {
        assertTrue(bracketsBalanced("1 + 1"))
        assertTrue(bracketsBalanced("todoService.findAll()"))
        assertTrue(bracketsBalanced(""))
    }

    @Test
    fun `open brackets continue the snippet`() {
        assertFalse(bracketsBalanced("listOf("))
        assertFalse(bracketsBalanced("listOf(1,"))
        assertFalse(bracketsBalanced("mapOf(\n  1 to 2"))
        assertFalse(bracketsBalanced("fun f() {"))
        assertFalse(bracketsBalanced("arrayOf[1"))
    }

    @Test
    fun `closed brackets are complete again`() {
        assertTrue(bracketsBalanced("listOf(\n  1,\n  2\n)"))
        assertTrue(bracketsBalanced("fun f() {\n  return 1\n}"))
    }

    @Test
    fun `brackets inside strings and chars are ignored`() {
        assertTrue(bracketsBalanced("val s = \"(\""))
        assertTrue(bracketsBalanced("val s = \"(\" + \")\""))
        assertTrue(bracketsBalanced("val c = '('"))
        assertTrue(bracketsBalanced("val s = \"\\\"(\""))
    }

    @Test
    fun `an unterminated string continues the snippet`() {
        assertFalse(bracketsBalanced("val s = \"oops"))
    }

    @Test
    fun `extra closing brackets terminate instead of waiting for more input`() {
        assertTrue(bracketsBalanced("foo)"))
        assertTrue(bracketsBalanced("foo}"))
    }
}
