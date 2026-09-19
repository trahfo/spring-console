package io.github.springconsole.engine

/**
 * Preprocesses Java-style variable/field declarations into valid Kotlin declarations
 * so that users and agents can type Java syntax (e.g. `private String x;`) into the
 * REPL and have it create an accessible variable rather than a compilation error.
 */
object JavaDeclarationPreprocessor {

    private val PRIMITIVE_TYPES = mapOf(
        "int" to ("Int" to "0"),
        "long" to ("Long" to "0L"),
        "boolean" to ("Boolean" to "false"),
        "double" to ("Double" to "0.0"),
        "float" to ("Float" to "0.0f"),
        "byte" to ("Byte" to "0"),
        "short" to ("Short" to "0"),
        "char" to ("Char" to "'\\u0000'"),
    )

    private val JAVA_MODIFIERS = setOf(
        "public", "protected", "private", "static", "final", "transient", "volatile",
    )

    // Regex for matching Java-style variable declarations:
    // e.g. "private String x;" or "int count = 10;" or "final List<String> items = new ArrayList<>();"
    private val JAVA_DECLARATION_REGEX = Regex(
        """^\s*((?:(?:public|protected|private|static|final|transient|volatile)\s+)*)""" +
            """([A-Za-z0-9_<>., ?\[\]]+)\s+""" +
            """([A-Za-z_][A-Za-z0-9_]*)\s*""" +
            """(?:=\s*([\s\S]*?))?\s*;?\s*$"""
    )

    // Kotlin keywords that should not be treated as Java type names
    private val KOTLIN_NON_TYPE_KEYWORDS = setOf(
        "val", "var", "fun", "return", "throw", "package", "import", "class", "interface",
        "object", "for", "while", "do", "if", "else", "when", "try", "catch", "finally",
    )

    data class DeclarationInfo(
        val name: String,
        val typeName: String,
        val kotlinCode: String,
    )

    /**
     * Inspects [code] and, if it matches a Java-style variable declaration,
     * translates it to Kotlin. Returns the translated code (or original if no match).
     */
    fun preprocess(code: String): String {
        val trimmed = code.trim()
        val info = parseDeclaration(trimmed) ?: return cleanMethodCalls(trimmed)
        return info.kotlinCode
    }

    /**
     * Attempts to parse [code] as a Java declaration, returning [DeclarationInfo] if successful.
     */
    fun parseDeclaration(code: String): DeclarationInfo? {
        val trimmed = code.trim()
        val match = JAVA_DECLARATION_REGEX.matchEntire(trimmed) ?: return null

        val modifiersStr = match.groupValues[1]
        val rawType = match.groupValues[2].trim()
        val name = match.groupValues[3].trim()
        val initializer = match.groupValues[4].takeIf { it.isNotBlank() }?.trim()?.removeSuffix(";")?.trim()

        val modifiers = modifiersStr.split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        val isFinal = "final" in modifiers

        // If the "type" starts with a Kotlin keyword like val, var, fun, return, do not treat as Java
        val firstTypeToken = rawType.split(Regex("[\\s<>\\[\\]]")).firstOrNull() ?: ""
        if (firstTypeToken in KOTLIN_NON_TYPE_KEYWORDS) {
            return null
        }

        val kotlinType = mapTypeToKotlin(rawType)
        val keyword = if (isFinal) "val" else "var"

        val kotlinCode = if (initializer != null) {
            val cleanInitializer = cleanInitializer(initializer)
            "$keyword $name: $kotlinType = $cleanInitializer"
        } else {
            val primitive = PRIMITIVE_TYPES[rawType]
            if (primitive != null) {
                "$keyword $name: ${primitive.first} = ${primitive.second}"
            } else {
                "$keyword $name: $kotlinType? = null"
            }
        }

        return DeclarationInfo(
            name = name,
            typeName = kotlinType,
            kotlinCode = kotlinCode,
        )
    }

    private fun mapTypeToKotlin(javaType: String): String {
        val trimmed = javaType.trim()
        PRIMITIVE_TYPES[trimmed]?.let { return it.first }

        if (trimmed.endsWith("[]")) {
            val element = trimmed.substring(0, trimmed.length - 2).trim()
            return when (element) {
                "int" -> "IntArray"
                "long" -> "LongArray"
                "boolean" -> "BooleanArray"
                "double" -> "DoubleArray"
                "float" -> "FloatArray"
                "byte" -> "ByteArray"
                "short" -> "ShortArray"
                "char" -> "CharArray"
                else -> "Array<${mapTypeToKotlin(element)}>"
            }
        }

        return trimmed
    }

    private fun cleanInitializer(initializer: String): String =
        cleanMethodCalls(initializer)

    private fun cleanMethodCalls(code: String): String {
        var clean = code
        // e.g. "new ArrayList<>()" -> "ArrayList()"
        if (clean.startsWith("new ")) {
            clean = clean.substring(4).trim()
            clean = clean.replace("<>", "")
        }
        clean = GET_ONE_CALLS.replace(clean) { match ->
            val prefix = match.groupValues[1]
            val arg = match.groupValues[2].trim().removeSuffix("L").removeSuffix("l")
            "${prefix}findById(${arg}L).get()"
        }
        return LONG_ID_METHOD_CALLS.replace(clean) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}L"
        }
    }

    private val GET_ONE_CALLS = Regex(
        """(\b[A-Za-z0-9_`]+\.)(?:getOne|getReferenceById)\(\s*(\d+[Ll]?)\s*\)"""
    )

    private val LONG_ID_METHOD_CALLS = Regex(
        """(\b(?:findById|deleteById|delete|toggle|update)\()\s*(\d+)\s*(?=[,)])"""
    )
}
