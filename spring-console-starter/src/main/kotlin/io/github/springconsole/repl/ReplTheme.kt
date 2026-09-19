package io.github.springconsole.repl

import org.jline.utils.AttributedStyle

/**
 * The single source of truth for every color used by the terminal REPL.
 *
 * ## Why a dedicated palette?
 *
 * Colors were previously sprinkled through `ConsoleRepl` as raw
 * `AttributedStyle.DEFAULT.foreground(...)` calls. Centralizing them has three
 * concrete benefits:
 *
 * 1. **Consistency** — the syntax highlighter, the result renderer, and the
 *    command handler all agree on what "a string" or "an error" looks like.
 * 2. **Readability** — the same semantic token (a key, a keyword, a null)
 *    keeps the same color everywhere, which is what makes long console output
 *    scannable.
 * 3. **Portability** — only the eight ANSI base colors are used (plus the
 *    *bright* variants and text attributes). JLine downgrades these correctly
 *    on terminals that support fewer colors, and they never rely on 256-color
 *    or true-color support.
 *
 * All styles are immutable; they are stored as `val`s computed once at class
 * initialization. Colors map to intent as follows:
 *
 * | Group          | Style                                  |
 * |----------------|----------------------------------------|
 * | success/value  | green                                  |
 * | informational  | cyan                                   |
 * | error          | red (+ underline for a marker)         |
 * | dim/secondary  | bright black ("gray") / faint          |
 * | warning        | yellow                                 |
 * | keyword        | magenta bold                           |
 * | string/char    | green                                  |
 * | number         | yellow                                 |
 * | boolean        | blue bold                              |
 * | comment        | bright black italic                    |
 * | annotation     | yellow bold                            |
 * | type name      | blue bold                              |
 * | property key   | cyan                                   |
 * | table header   | white bold underline                   |
 */
object ReplTheme {

    // ------------------------------------------------------------------
    // Interface chrome (prompts, banners, diagnostics)
    // ------------------------------------------------------------------

    /** The `spring-console>` prompt (and its `!` sandbox-off variant). */
    val prompt: AttributedStyle = fg(AttributedStyle.GREEN).bold()

    /** Positive outcomes: successful evaluations, reload success. */
    val success: AttributedStyle = fg(AttributedStyle.GREEN)

    /** Neutral, informational text such as hints and sandbox notes. */
    val info: AttributedStyle = fg(AttributedStyle.CYAN)

    /** Failures: compile errors, runtime exceptions, rejected commands. */
    val error: AttributedStyle = fg(AttributedStyle.RED)

    /** The `^` caret placed under the column named by a compile error. */
    val errorMarker: AttributedStyle = fg(AttributedStyle.RED).bold().underline()

    /** Non-fatal warnings (unknown command, missing argument, unavailable feature). */
    val warning: AttributedStyle = fg(AttributedStyle.YELLOW)

    /** Secondary detail: stack frames, timings, truncation notices. */
    val dim: AttributedStyle = fg(AttributedStyle.BRIGHT)

    /** Even quieter secondary detail (faint attribute where supported). */
    val faint: AttributedStyle = fg(AttributedStyle.BRIGHT).faint()

    /** The horizontal/vertical rules of rendered tables. */
    val border: AttributedStyle = fg(AttributedStyle.BRIGHT)

    // ------------------------------------------------------------------
    // Syntax highlighting
    // ------------------------------------------------------------------

    /** Kotlin hard keywords (`fun`, `val`, `if`, `return`, …). */
    val keyword: AttributedStyle = fg(AttributedStyle.MAGENTA).bold()

    /** Kotlin soft keywords and modifiers (`data`, `suspend`, `inline`, …). */
    val softKeyword: AttributedStyle = fg(AttributedStyle.MAGENTA)

    /** String and character literals. */
    val string: AttributedStyle = fg(AttributedStyle.GREEN)

    /** Numeric literals. */
    val number: AttributedStyle = fg(AttributedStyle.YELLOW)

    /** `true` / `false` / `null` literals. */
    val literal: AttributedStyle = fg(AttributedStyle.CYAN).bold()

    /** `//` line and `/* */` block comments. */
    val comment: AttributedStyle = fg(AttributedStyle.BRIGHT).italic()

    /** `@Annotation` usages. */
    val annotation: AttributedStyle = fg(AttributedStyle.YELLOW).bold()

    /** Backticked identifiers (`` `weird name` ``). */
    val backticked: AttributedStyle = fg(AttributedStyle.CYAN)

    /** Plain identifiers: variable names, bean names, member names. */
    val identifier: AttributedStyle = AttributedStyle.DEFAULT

    // ------------------------------------------------------------------
    // Result rendering
    // ------------------------------------------------------------------

    /** The type header of a rendered object or table. */
    val typeName: AttributedStyle = fg(AttributedStyle.BLUE).bold()

    /** A property/key column in a key-value block. */
    val key: AttributedStyle = fg(AttributedStyle.CYAN)

    /** Table column headers. */
    val header: AttributedStyle = fg(AttributedStyle.WHITE).bold().underline()

    /** A string value. */
    val valueString: AttributedStyle = fg(AttributedStyle.GREEN)

    /** A numeric value. */
    val valueNumber: AttributedStyle = fg(AttributedStyle.YELLOW)

    /** A boolean value. */
    val valueBoolean: AttributedStyle = fg(AttributedStyle.BLUE).bold()

    /** An enum value. */
    val valueEnum: AttributedStyle = fg(AttributedStyle.MAGENTA)

    /** A date/time value. */
    val valueTemporal: AttributedStyle = fg(AttributedStyle.CYAN)

    /** The `null` literal in rendered output. */
    val valueNull: AttributedStyle = fg(AttributedStyle.BRIGHT).faint()

    /** Truncation/summary markers such as `… and 12 more`. */
    val valueSummary: AttributedStyle = fg(AttributedStyle.BRIGHT)

    /** The `= Unit` / "no value" placeholder. */
    val valueNone: AttributedStyle = fg(AttributedStyle.BRIGHT)

    /** A convenience alias: `DEFAULT.foreground(color)`. */
    private fun fg(color: Int): AttributedStyle = AttributedStyle.DEFAULT.foreground(color)
}
