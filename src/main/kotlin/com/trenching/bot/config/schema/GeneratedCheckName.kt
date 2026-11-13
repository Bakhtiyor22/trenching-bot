package com.trenching.bot.config.schema

/**
 * The names PostgreSQL gives an unnamed CHECK over one column, which is how the checks Hibernate creates for enum columns are
 * named (`ChooseConstraintName`): `<table>_<column>_check`, then `_check1`, `_check2` on a clash (up to nine are recognised),
 * with the longer of table and column cut first so the name fits 63 bytes. Lengths are counted in characters, which equal bytes
 * for the ASCII names used here; a long non-ASCII name that PostgreSQL cut by bytes does not match, so its check is left alone.
 */
internal object GeneratedCheckName {
    private const val MAX_LENGTH = 63
    private const val SEPARATORS = 2
    private const val LABEL = "check"
    private const val CLASHES = 9

    fun matches(name: String, table: String, column: String): Boolean =
        (0..CLASHES).any { clash -> name == of(table, column, if (clash == 0) LABEL else "$LABEL$clash") }

    private fun of(table: String, column: String, label: String): String {
        val available = MAX_LENGTH - SEPARATORS - label.length
        var tableLength = table.length
        var columnLength = column.length
        while (tableLength + columnLength > available) {
            if (tableLength > columnLength) tableLength-- else columnLength--
        }
        return "${table.take(tableLength)}_${column.take(columnLength)}_$label"
    }
}
