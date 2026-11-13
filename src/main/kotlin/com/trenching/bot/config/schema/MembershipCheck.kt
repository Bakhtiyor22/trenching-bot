package com.trenching.bot.config.schema

/** Reads the values of an enum membership check, as Hibernate writes it and as PostgreSQL prints it back. */
internal object MembershipCheck {
    private val LITERAL = Regex("'((?:[^']|'')*)'")
    private val MAPPED = Regex("""\S+ in \(\s*'(?:[^']|'')*'(?:\s*,\s*'(?:[^']|'')*')*\s*\)""", RegexOption.IGNORE_CASE)
    private val CAST = Regex("""::(?:character varying|varchar|bpchar|text|name)(?:\(\d+\))?(?:\[])?""")
    private val KEYWORD = Regex("""\b(?:CHECK|NOT VALID|ANY|ARRAY)\b""")
    private val PUNCTUATION = Regex("""[()\[\],=\s]""")

    /** The values of the condition Hibernate generates, or null when [condition] is not a plain membership list. */
    fun mappedValues(condition: String): Set<String>? = if (MAPPED.matches(condition.trim())) literals(condition) else null

    /**
     * The values a stored check allows, or null when [definition] is anything but a membership list over [column]. PostgreSQL
     * prints such a list as `= ANY (ARRAY[...])` with casts whose placement changes after a dump and restore, so the definition
     * counts as one when nothing but the column, the literals and those tokens is left of it.
     */
    fun storedValues(definition: String, column: String): Set<String>? {
        val identifier = Regex("\"?\\b" + Regex.escape(column) + "\\b\"?")
        val rest = listOf(CAST, KEYWORD, identifier, PUNCTUATION).fold(LITERAL.replace(definition, "")) { text, token -> token.replace(text, "") }
        return literals(definition).takeIf { rest.isEmpty() && it.isNotEmpty() }
    }

    private fun literals(text: String): Set<String> = LITERAL.findAll(text).map { it.groupValues[1].replace("''", "'") }.toSet()
}
