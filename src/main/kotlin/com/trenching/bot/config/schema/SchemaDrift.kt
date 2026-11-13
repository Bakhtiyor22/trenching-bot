package com.trenching.bot.config.schema

/**
 * `ddl-auto: update` adds tables and columns but never revisits two things it created earlier: the membership check of an enum
 * column and a NOT NULL. A database that lived through a release which added an enum constant, or made a column nullable, then
 * rejects rows the current entities write. These are the statements that relax exactly those constraints and nothing else: a
 * check that already allows every mapped value is kept, also when it still lists values the enum has dropped.
 */
object SchemaDrift {
    private const val NOT_NULL = "NOT_NULL"
    private const val NULL = "NULL"

    fun repairs(mapped: Collection<MappedColumn>, stored: StoredConstraints): List<SchemaRepair> {
        val checks = stored.columnChecks.groupBy { ColumnId(it.table, it.column) }
        return mapped.sortedWith(compareBy({ it.table }, { it.column })).flatMap { column ->
            val id = ColumnId(column.table, column.column)
            staleChecks(column, checks[id].orEmpty()) + listOfNotNull(leftNotNull(column, id, stored))
        }
    }

    /**
     * Only a check PostgreSQL named for its column is one Hibernate created; a named check such as `ck_paper_fills_status` is a
     * rule written on purpose and is never widened. The replacement allows the mapped values and every value the old check
     * allowed, so a repair only ever widens: a build mapping other constants, such as another branch on the same database, adds
     * its own instead of removing these. It is added NOT VALID, so the stored rows are not scanned under the table lock.
     */
    private fun staleChecks(column: MappedColumn, stored: List<StoredColumnCheck>): List<SchemaRepair> {
        val condition = column.membershipCheck ?: return emptyList()
        val mappedValues = MembershipCheck.mappedValues(condition) ?: return emptyList()
        return stored.sortedBy { it.name }
            .filter { check -> GeneratedCheckName.matches(check.name, check.table, check.column) }
            .mapNotNull { check ->
                val allowed = MembershipCheck.storedValues(check.definition, check.column) ?: return@mapNotNull null
                val added = mappedValues - allowed
                if (added.isEmpty()) null else replacement(check, mappedValues + allowed, added)
            }
    }

    private fun replacement(check: StoredColumnCheck, allowed: Set<String>, added: Set<String>): SchemaRepair {
        val values = allowed.joinToString(",") { "'" + it.replace("'", "''") + "'" }
        return SchemaRepair(check.table, check.column, check.name, "alter table ${quoted(check.table)} drop constraint ${quoted(check.name)}, " +
            "add constraint ${quoted(check.name)} check (${quoted(check.column)} in ($values)) not valid", added.toList())
    }

    /** A key column's NOT NULL is structural, not drift, and PostgreSQL would refuse to drop it, so it is left alone. */
    private fun leftNotNull(column: MappedColumn, id: ColumnId, stored: StoredConstraints): SchemaRepair? =
        if (column.nullable && id in stored.notNullColumns && id !in stored.keyColumns) {
            SchemaRepair(column.table, column.column, NOT_NULL,
                "alter table ${quoted(column.table)} alter column ${quoted(column.column)} drop not null", listOf(NULL))
        } else {
            null
        }

    private fun quoted(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""
}
