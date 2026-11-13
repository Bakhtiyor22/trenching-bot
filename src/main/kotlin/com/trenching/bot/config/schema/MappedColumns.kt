package com.trenching.bot.config.schema

import org.hibernate.boot.Metadata

/** Reads from Hibernate's boot model what it would create today for every mapped column. */
internal object MappedColumns {
    fun of(metadata: Metadata): List<MappedColumn> = metadata.collectTableMappings().filter { it.isPhysicalTable }.flatMap { table ->
        table.columns.map { column ->
            MappedColumn(
                table = stored(table.name, table.isQuoted),
                column = stored(column.name, column.isQuoted),
                nullable = column.isNullable,
                membershipCheck = column.checkConstraints.map { it.constraint }.firstOrNull { MembershipCheck.mappedValues(it) != null },
            )
        }
    }

    /** PostgreSQL folds an unquoted identifier to lower case. */
    private fun stored(name: String, quoted: Boolean): String = if (quoted) name else name.lowercase()
}
