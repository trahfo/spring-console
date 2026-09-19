package io.github.springconsole.repl

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder
import org.jline.utils.AttributedStyle
import org.springframework.aop.support.AopUtils
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Renders the value produced by a REPL evaluation into a color-coded,
 * human-readable terminal view.
 *
 * This is the component behind the acceptance criterion *"when a method
 * returns a variable, or the variable is just named and Enter is pressed, the
 * content of that variable is presented in a nice view in the terminal, with
 * color coding for readability."*
 *
 * Typing…
 *
 * ```text
 * todoService.findAll()      // a List<Todo>   → a table, one row per element
 * todoService.stats()        // a record       → a key/value block
 * todoService                // a bean         → type header + method signatures
 * "hello"                    // a String       → green, quoted
 * mapOf("a" to 1)            // a Map          → two-column table
 * ```
 *
 * …each produces a shape chosen from the runtime value, never from static
 * knowledge:
 *
 * | Runtime shape                                   | Rendered as                       |
 * |-------------------------------------------------|-----------------------------------|
 * | `null`                                          | dim `null`                        |
 * | scalar (string, number, boolean, char, enum)    | one colored line                  |
 * | `Map`                                           | `key`/`value` table               |
 * | `Iterable`, array, `Sequence`, Java `Stream`    | table, columns from element props |
 * | `Optional`                                      | its contained value               |
 * | Spring Data `Slice`/`Page`                      | page header + content table       |
 * | `Throwable`                                     | red message + dim frames          |
 * | anything else                                   | type header + key/value block     |
 *
 * ### Safety
 *
 * The renderer runs *after* the evaluation's transaction has been rolled back,
 * so lazily-loaded JPA state may no longer be reachable. Every reflective read
 * is individually guarded: a property that throws is shown as
 * `<unavailable: …>` in red instead of aborting the whole rendering. Hibernate
 * proxies are detected *before* any getter is called so that rendering never
 * triggers a `LazyInitializationException` cascade or an accidental N+1 query.
 *
 * Output is bounded by [maxRows], [maxColumns], [maxCellWidth] and
 * [maxStringLength], with explicit truncation notices, so a 10 000-row result
 * cannot flood the terminal.
 *
 * ### Testability
 *
 * [render] returns JLine [AttributedString]s. Tests can inspect per-character
 * styles via [AttributedString.styleAt] and real ANSI output via [renderAnsi];
 * neither requires a terminal.
 *
 * @param maxRows       maximum table rows (and collection elements) shown.
 * @param maxColumns    maximum property columns shown for a table.
 * @param maxCellWidth  maximum printable width of a single table cell.
 * @param maxStringLength maximum length of a directly rendered string before it is truncated.
 * @param maxMethods    maximum method signatures listed for an object without properties.
 */
class ResultRenderer(
    private val maxRows: Int = 50,
    private val maxColumns: Int = 8,
    private val maxCellWidth: Int = 48,
    private val maxStringLength: Int = 200,
    private val maxMethods: Int = 12,
) {

    /**
     * Renders [value] into zero or more lines. An empty list means "nothing to
     * show" (a `Unit`-returning expression).
     */
    fun render(value: Any?): List<AttributedString> {
        return try {
            renderValue(value)
        } catch (e: Exception) {
            failure(e)
        } catch (e: LinkageError) {
            failure(e)
        }
    }

    /** Convenience for logging/tests: [render] joined with newlines and ANSI-encoded. */
    fun renderAnsi(value: Any?): String =
        render(value).joinToString("\n") { it.toAnsi() }

    // ------------------------------------------------------------------
    // Dispatch
    // ------------------------------------------------------------------

    private fun renderValue(value: Any?): List<AttributedString> = when {
        value == null -> listOf(line("null", ReplTheme.valueNull))
        value is Unit -> emptyList()
        value is String -> stringLines(value)
        value is Char -> listOf(line("'$value'", ReplTheme.valueString))
        value is CharSequence -> stringLines(value.toString())
        value is Boolean -> listOf(line(value.toString(), ReplTheme.valueBoolean))
        value is Number -> listOf(line(numberText(value), ReplTheme.valueNumber))
        value is Enum<*> -> listOf(line("${value.javaClass.simpleName}.${value.name}", ReplTheme.valueEnum))
        value is Throwable -> throwableLines(value)
        value is java.util.Optional<*> -> optionalLines(value)
        value is Map<*, *> -> mapLines(value)
        isSlice(value) -> sliceLines(value)
        value is Sequence<*> -> tableFromIterable(value.take(maxRows + 1).toList(), "Sequence", exactSize = false)
        value is Collection<*> -> {
            val elements = value.toList()
            tableFromIterable(elements, collectionTitle(value, elements))
        }
        value is Iterable<*> -> tableFromIterable(value.take(maxRows + 1).toList(), "Iterable", exactSize = false)
        value is java.util.stream.BaseStream<*, *> -> streamLines(value)
        value.javaClass.isArray -> tableFromIterable(arrayToList(value), "${value.javaClass.componentType.simpleName}[]")
        value is Pair<*, *> -> objectEntries(simpleName(value), listOf("first" to value.first, "second" to value.second))
        value is Triple<*, *, *> -> objectEntries(
            simpleName(value),
            listOf("first" to value.first, "second" to value.second, "third" to value.third),
        )
        isTemporal(value) -> listOf(line(value.toString(), ReplTheme.valueTemporal))
        value is Class<*> -> listOf(line(value.name, ReplTheme.typeName))
        value is java.util.UUID -> listOf(line(value.toString(), ReplTheme.valueTemporal))
        value is java.io.File -> listOf(line(value.path, ReplTheme.valueString))
        else -> objectLines(value)
    }

    /** Human-readable, bounded description of a value used inside a single cell. */
    private fun cellFor(value: Any?, quoteStrings: Boolean): Cell =
        if (value is Unavailable) Cell(value.message, ReplTheme.error)
        else scalarOrSummary(value, quoteStrings)

    private fun scalarOrSummary(value: Any?, quoteStrings: Boolean): Cell = when {
        value == null -> Cell("null", ReplTheme.valueNull)
        value is String -> Cell(truncate(if (quoteStrings) "\"${escape(value)}\"" else escape(value), maxCellWidth), ReplTheme.valueString)
        value is Char -> Cell("'$value'", ReplTheme.valueString)
        value is CharSequence -> Cell(truncate(if (quoteStrings) "\"${escape(value.toString())}\"" else escape(value.toString()), maxCellWidth), ReplTheme.valueString)
        value is Boolean -> Cell(value.toString(), ReplTheme.valueBoolean)
        value is Number -> Cell(numberText(value), ReplTheme.valueNumber)
        value is Enum<*> -> Cell("${value.javaClass.simpleName}.${value.name}", ReplTheme.valueEnum)
        isTemporal(value) -> Cell(value.toString(), ReplTheme.valueTemporal)
        value is Class<*> -> Cell(value.name, ReplTheme.typeName)
        value is Map<*, *> -> Cell("[${value.size} entries]", ReplTheme.valueSummary)
        value is Collection<*> -> Cell("[${value.size} items]", ReplTheme.valueSummary)
        value.javaClass.isArray -> Cell("[${arrayLength(value)} items]", ReplTheme.valueSummary)
        else -> Cell(summary(value), ReplTheme.valueSummary)
    }

    private fun isScalar(value: Any?): Boolean = when (value) {
        null, is String, is Char, is CharSequence, is Boolean, is Number, is Enum<*> -> true
        else -> isTemporal(value) || value is Class<*> || value is java.util.UUID || value is java.io.File
    }

    // ------------------------------------------------------------------
    // Scalars and strings
    // ------------------------------------------------------------------

    private fun stringLines(value: String): List<AttributedString> {
        val escaped = escape(value)
        val shown = truncate(escaped, maxStringLength)
        val builder = AttributedStringBuilder()
        builder.styled(ReplTheme.valueString, "\"$shown\"")
        if (shown != escaped) {
            builder.styled(ReplTheme.valueSummary, " … (${value.length} chars)")
        }
        return listOf(builder.toAttributedString())
    }

    private fun numberText(value: Number): String = when (value) {
        is Double -> if (value == value.toLong().toDouble()) "${value.toLong()} (Double)" else value.toString()
        is Float -> if (value == value.toLong().toFloat()) "${value.toLong()} (Float)" else value.toString()
        is Long -> "$value (Long)"
        else -> value.toString()
    }

    /** Renders newlines/tabs visibly so multi-line strings do not break the layout. */
    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    // ------------------------------------------------------------------
    // Optional
    // ------------------------------------------------------------------

    private fun optionalLines(optional: java.util.Optional<*>): List<AttributedString> {
        if (!optional.isPresent) return listOf(line("Optional.empty", ReplTheme.valueNull))
        val inner = renderValue(guarded { optional.get() })
        if (inner.isEmpty()) return listOf(line("Optional<Unit>", ReplTheme.valueNull))
        return listOf(line("Optional", ReplTheme.typeName)) + inner.map { it }
    }

    // ------------------------------------------------------------------
    // Maps
    // ------------------------------------------------------------------

    private fun mapLines(map: Map<*, *>): List<AttributedString> {
        val entries = map.entries.toList()
        val rows = entries.take(maxRows).map { it.key to it.value }
        val columns = listOf(
            Column("key") { pair -> (pair as Pair<*, *>).first },
            Column("value") { pair -> (pair as Pair<*, *>).second },
        )
        val lines = ArrayList<AttributedString>()
        lines += line("Map (${map.size} ${if (map.size == 1) "entry" else "entries"})", ReplTheme.typeName)
        lines += table(columns, rows)
        lines += truncationNotice(entries.size, rows.size, "entries")
        return lines
    }

    // ------------------------------------------------------------------
    // Spring Data Slice/Page (reflection: spring-data-commons is optional)
    // ------------------------------------------------------------------

    private fun isSlice(value: Any): Boolean =
        value.javaClass.interfaces.any { it.name == "org.springframework.data.domain.Slice" }

    private fun sliceLines(value: Any): List<AttributedString> {
        val content = guarded { value.javaClass.getMethod("getContent").invoke(value) } as? Collection<*> ?: return objectLines(value)
        val number = (guarded { value.javaClass.getMethod("getNumber").invoke(value) } as? Number)?.toInt()
        val totalPages = (guarded { value.javaClass.getMethod("getTotalPages").invoke(value) } as? Number)?.toInt()
        val totalElements = (guarded { value.javaClass.getMethod("getTotalElements").invoke(value) } as? Number)?.toLong()
        val size = (guarded { value.javaClass.getMethod("getSize").invoke(value) } as? Number)?.toInt()
        val header = buildString {
            append(simpleName(value))
            if (number != null && totalPages != null) append(" — page ${number + 1}/$totalPages")
            if (totalElements != null) append(" (${totalElements} total)")
            if (size != null) append(", size $size")
        }
        return listOf(line(header, ReplTheme.typeName)) + tableFromIterable(content.toList(), null)
    }

    // ------------------------------------------------------------------
    // Collections, arrays, streams
    // ------------------------------------------------------------------

    private fun streamLines(stream: java.util.stream.BaseStream<*, *>): List<AttributedString> {
        val elements = (guarded {
            val iterator = stream.iterator()
            buildList {
                while (iterator.hasNext() && size <= maxRows) add(iterator.next())
            }
        } as? List<Any?>) ?: emptyList()
        guarded { stream.close() }
        return tableFromIterable(elements, "Stream", exactSize = false)
    }

    private fun arrayLength(array: Any): Int = java.lang.reflect.Array.getLength(array)

    /** Friendly collection title such as `List<Todo>`; falls back to `List` when empty. */
    private fun collectionTitle(value: Collection<*>, elements: List<Any?>): String {
        val kind = when {
            value is Set<*> -> "Set"
            value is List<*> -> "List"
            else -> "Collection"
        }
        val elementType = elements.firstOrNull { it != null }?.let { simpleName(it) }
        return if (elementType != null) "$kind<$elementType>" else kind
    }

    private fun arrayToList(array: Any): List<Any?> {
        val length = arrayLength(array)
        return (0 until length).map { guarded { java.lang.reflect.Array.get(array, it) } }
    }

    /**
     * Renders a sequence of elements as a table.
     *
     * @param exactSize true when [elements] is fully materialized (collection,
     *   array) so its size is the true total; false for lazy sources
     *   (`Iterable`, `Sequence`, Java `Stream`) where only `maxRows + 1`
     *   elements were pulled and the real total is unknown.
     */
    private fun tableFromIterable(elements: List<Any?>, title: String?, exactSize: Boolean = true): List<AttributedString> {
        val shown = elements.take(maxRows)
        val lines = ArrayList<AttributedString>()
        if (title != null) {
            val known = if (exactSize) elements.size else shown.size
            val noun = if (known == 1) "item" else "items"
            val header = when {
                exactSize -> "$title (${elements.size} $noun)"
                elements.size > shown.size -> "$title (showing ${shown.size}+ $noun)"
                else -> "$title (showing ${shown.size} $noun)"
            }
            lines += line(header, ReplTheme.typeName)
        }
        lines += if (shown.isEmpty()) {
            listOf(line("  (empty)", ReplTheme.valueSummary))
        } else {
            table(columnsFor(shown), shown)
        }
        lines += when {
            exactSize -> truncationNotice(elements.size, shown.size, "items")
            elements.size > shown.size -> listOf(line("  … more items not shown", ReplTheme.valueSummary))
            else -> emptyList()
        }
        return lines
    }

    /**
     * Chooses table columns: scalar collections get a single `value` column;
     * object collections derive one column per readable property of the first
     * non-scalar element type.
     */
    private fun columnsFor(rows: List<Any?>): List<Column> {
        if (rows.all { isScalar(it) }) return listOf(Column("value") { it })
        val type = rows.firstOrNull { it != null && !isScalar(it) }?.javaClass ?: return listOf(Column("value") { it })
        val properties = readableProperties(type).take(maxColumns)
        if (properties.isEmpty()) return listOf(Column("value") { it })
        return properties.map { property ->
            Column(property.name) { row ->
                if (row == null) null
                else if (!property.getter.declaringClass.isInstance(row)) Unavailable("not a ${type.simpleName}")
                else readProperty(row, property)
            }
        }
    }

    // ------------------------------------------------------------------
    // Tables
    // ------------------------------------------------------------------

    private class Column(val name: String, val extract: (Any?) -> Any?)

    private fun table(columns: List<Column>, rows: List<Any?>): List<AttributedString> {
        val cells: List<List<Cell>> = rows.map { row ->
            columns.map { column ->
                val raw = guarded { column.extract(row) }
                cellFor(raw, quoteStrings = false)
            }
        }
        val headerCells = columns.map { column ->
            // Append the column's runtime type for object tables, which makes
            // unit/type mistakes obvious at a glance.
            val typeHint = rows.firstOrNull { it != null && !isScalar(it) }
                ?.let { readableProperties(it.javaClass).firstOrNull { p -> p.name == column.name }?.type?.simpleName }
            if (typeHint != null) Cell("${column.name}: $typeHint", ReplTheme.header) else Cell(column.name, ReplTheme.header)
        }
        val widths = IntArray(columns.size) { index ->
            val headerWidth = headerCells[index].text.length
            val cellWidth = cells.maxOfOrNull { it[index].text.length } ?: 0
            minOf(maxOf(headerWidth, cellWidth), maxCellWidth)
        }

        val builder = ArrayList<AttributedString>(cells.size + 2)
        builder += joinRow(headerCells, widths)
        builder += ruleRow(widths)
        cells.forEach { row -> builder += joinRow(row, widths) }
        return builder
    }

    private fun joinRow(cells: List<Cell>, widths: IntArray): AttributedString {
        val builder = AttributedStringBuilder()
        cells.forEachIndexed { index, cell ->
            if (index > 0) builder.styled(ReplTheme.border, " │ ")
            val text = pad(cell.text, widths[index])
            builder.styled(cell.style, text)
        }
        return builder.toAttributedString()
    }

    private fun ruleRow(widths: IntArray): AttributedString {
        val builder = AttributedStringBuilder()
        widths.forEachIndexed { index, width ->
            if (index > 0) builder.styled(ReplTheme.border, "─┼─")
            builder.styled(ReplTheme.border, "─".repeat(width))
        }
        return builder.toAttributedString()
    }

    private fun pad(text: String, width: Int): String =
        if (text.length >= width) text else text + " ".repeat(width - text.length)

    // ------------------------------------------------------------------
    // Generic objects
    // ------------------------------------------------------------------

    private fun objectLines(value: Any): List<AttributedString> {
        val targetType = try {
            AopUtils.getTargetClass(value)
        } catch (e: Exception) {
            value.javaClass
        }
        val proxied = targetType != value.javaClass
        val properties = readableProperties(targetType)

        if (properties.isEmpty()) {
            val allMethods = callableMethods(targetType)
            val methods = allMethods.take(maxMethods)
            val header = buildString {
                append(simpleName(value))
                append("  (${allMethods.size} ${if (allMethods.size == 1) "method" else "methods"}")
                if (proxied) append(", Spring proxy")
                append(")")
            }
            val lines = ArrayList<AttributedString>()
            lines += line(header, ReplTheme.typeName)
            if (isHibernateProxy(value.javaClass)) {
                lines += line("  (Hibernate lazy proxy — not initialized at render time)", ReplTheme.valueSummary)
            }
            if (methods.isEmpty()) {
                lines += line("  (no readable properties or callable methods)", ReplTheme.valueSummary)
            }
            methods.forEach { method ->
                val parameters = method.parameterTypes.joinToString(", ") { it.simpleName }
                val builder = AttributedStringBuilder()
                builder.styled(ReplTheme.identifier, "  ${method.name}(")
                builder.styled(ReplTheme.typeName, parameters)
                builder.styled(ReplTheme.identifier, "): ")
                builder.styled(ReplTheme.typeName, method.returnType.simpleName)
                lines += builder.toAttributedString()
            }
            return lines + truncationNotice(allMethods.size, methods.size, "methods")
        }

        val entries = properties.map { property -> property.name to readProperty(value, property) }
        return objectEntries(simpleName(value), entries, proxied = proxied)
    }

    private fun objectEntries(
        title: String,
        entries: List<Pair<String, Any?>>,
        proxied: Boolean = false,
    ): List<AttributedString> {
        val header = buildString {
            append(title)
            append("  (${entries.size} ${if (entries.size == 1) "property" else "properties"}")
            if (proxied) append(", Spring proxy")
            append(")")
        }
        val keyWidth = entries.maxOfOrNull { it.first.length }?.coerceAtMost(28) ?: 0
        val lines = ArrayList<AttributedString>(entries.size + 1)
        lines += line(header, ReplTheme.typeName)
        entries.forEach { (name, value) ->
            val cell = cellFor(value, quoteStrings = true)
            val builder = AttributedStringBuilder()
            builder.styled(ReplTheme.key, "  " + pad(name, keyWidth))
            builder.styled(ReplTheme.border, " = ")
            builder.styled(cell.style, cell.text)
            lines += builder.toAttributedString()
        }
        return lines
    }

    private fun throwableLines(value: Throwable): List<AttributedString> {
        val lines = ArrayList<AttributedString>()
        lines += line("${value.javaClass.name}: ${value.message ?: "(no message)"}", ReplTheme.error)
        value.stackTrace.take(10).forEach { frame -> lines += line("  at $frame", ReplTheme.dim) }
        return lines
    }

    // ------------------------------------------------------------------
    // Reflection helpers
    // ------------------------------------------------------------------

    /** A readable no-argument property exposed by a type. */
    private class Property(val name: String, val getter: Method, val type: Class<*>)

    private class Unavailable(val message: String)

    private fun readableProperties(type: Class<*>): List<Property> {
        val seen = HashSet<String>()
        val properties = ArrayList<Property>()

        // Java records: component order and accessor names are authoritative.
        if (type.isRecord) {
            type.recordComponents.forEach { component ->
                val accessor = component.accessor
                if (Modifier.isPublic(accessor.modifiers) && seen.add(component.name)) {
                    properties += Property(component.name, accessor, component.type)
                }
            }
            return properties
        }

        type.methods
            .filter { method ->
                method.parameterCount == 0 &&
                    Modifier.isPublic(method.modifiers) &&
                    !Modifier.isStatic(method.modifiers) &&
                    !method.isSynthetic &&
                    method.declaringClass != Any::class.java &&
                    !method.name.contains('$') &&
                    ReplReflection.isGetter(method) &&
                    method.name != "getClass"
            }
            .sortedBy { it.name }
            .forEach { method ->
                val name = ReplReflection.propertyName(method)
                if (seen.add(name)) properties += Property(name, method, method.returnType)
            }
        return properties
    }

    /** Invokes a getter, translating any failure into [Unavailable] for display. */
    private fun readProperty(instance: Any, property: Property): Any? =
        guarded { property.getter.invoke(instance) }

    private fun callableMethods(type: Class<*>): List<Method> =
        type.methods
            .filter {
                !it.isSynthetic &&
                    Modifier.isPublic(it.modifiers) &&
                    it.declaringClass != Any::class.java &&
                    !it.name.contains('$') &&
                    !ReplReflection.isGetter(it)
            }
            .sortedWith(compareBy({ it.name }, { it.parameterCount }))

    private fun isHibernateProxy(type: Class<*>): Boolean = ReplReflection.isHibernateProxy(type)

    private fun isTemporal(value: Any): Boolean =
        value is java.time.temporal.Temporal ||
            value is java.time.temporal.TemporalAmount ||
            value is java.util.Date ||
            value is java.util.Calendar ||
            value is java.time.ZoneId

    /**
     * Runs [block], returning [Unavailable] when it throws. Catches `Exception`
     * (reflection failures) and `LinkageError` (optional types missing at
     * runtime) without swallowing `Error`s that indicate a broken JVM.
     */
    private inline fun guarded(block: () -> Any?): Any? = try {
        block()
    } catch (e: Exception) {
        Unavailable(describeFailure(e))
    } catch (e: LinkageError) {
        Unavailable("unavailable type")
    }

    private fun describeFailure(e: Throwable): String {
        val cause = if (e is java.lang.reflect.InvocationTargetException) e.targetException ?: e else e
        val message = cause.message?.take(80)
        return if (message.isNullOrBlank()) "<unavailable: ${cause.javaClass.simpleName}>"
        else "<unavailable: ${cause.javaClass.simpleName}: $message>"
    }

    private fun simpleName(value: Any): String {
        val type = try {
            AopUtils.getTargetClass(value)
        } catch (e: Exception) {
            value.javaClass
        }
        return type.simpleName.ifBlank { type.name.substringAfterLast('.') }
    }

    /** `toString()` when the type overrides it, otherwise `Type@hash`. */
    private fun summary(value: Any): String {
        if (isHibernateProxy(value.javaClass)) return "${simpleName(value)} (lazy proxy)"
        val type = value.javaClass
        val overridesToString = try {
            type.getMethod("toString").declaringClass != Any::class.java
        } catch (e: Exception) {
            false
        }
        val text = if (overridesToString) {
            (guarded { value.toString() } as? String) ?: "${simpleName(value)}@${hash(value)}"
        } else {
            "${simpleName(value)}@${hash(value)}"
        }
        return truncate(text.replace('\n', ' '), maxCellWidth)
    }

    private fun hash(value: Any): String = Integer.toHexString(System.identityHashCode(value))

    // ------------------------------------------------------------------
    // Small utilities
    // ------------------------------------------------------------------

    private class Cell(val text: String, val style: AttributedStyle)

    private fun line(text: String, style: AttributedStyle): AttributedString =
        AttributedString(text, style)

    private fun truncationNotice(total: Int, shown: Int, noun: String): List<AttributedString> {
        val hidden = total - shown
        if (hidden <= 0) return emptyList()
        return listOf(line("  … and $hidden more $noun", ReplTheme.valueSummary))
    }

    private fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1) + "…"

    private fun failure(e: Throwable): List<AttributedString> =
        listOf(line("<could not render result: ${e.javaClass.simpleName}: ${e.message}>", ReplTheme.error))
}
