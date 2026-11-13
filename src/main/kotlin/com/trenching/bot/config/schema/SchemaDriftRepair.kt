package com.trenching.bot.config.schema

import java.sql.Connection
import java.sql.SQLException

/** Applies [SchemaDrift]'s repairs to the schema a connection is on. The caller owns the transaction and reports the repairs once it commits. */
class SchemaDriftRepair {
    fun repair(connection: Connection, mapped: Collection<MappedColumn>): List<SchemaRepair> {
        val repairs = SchemaDrift.repairs(mapped, stored(connection))
        if (repairs.isNotEmpty()) connection.createStatement().use { it.execute(LOCK_TIMEOUT) }
        repairs.forEach { repair ->
            try {
                connection.createStatement().use { it.execute(repair.sql) }
            } catch (exception: SQLException) {
                throw SchemaDriftRepairException(repair, exception)
            }
        }
        return repairs
    }

    private fun stored(connection: Connection): StoredConstraints = StoredConstraints(
        columnChecks = rows(connection, COLUMN_CHECKS) { StoredColumnCheck(it[0], it[1], it[2], it[3]) },
        notNullColumns = columnIds(connection, NOT_NULL_COLUMNS),
        keyColumns = columnIds(connection, KEY_COLUMNS),
    )

    private fun columnIds(connection: Connection, sql: String): Set<ColumnId> = rows(connection, sql) { ColumnId(it[0], it[1]) }.toSet()

    private fun <T> rows(connection: Connection, sql: String, row: (List<String>) -> T): List<T> =
        connection.prepareStatement(sql).use { statement ->
            statement.executeQuery().use { result ->
                val columns = result.metaData.columnCount
                generateSequence { if (result.next()) row((1..columns).map(result::getString)) else null }.toList()
            }
        }

    private companion object {
        /**
         * Each repair takes an exclusive lock on its table. A table another session keeps locked then fails the start within
         * seconds, instead of stalling it, and every query queued behind the repair, for as long as that session lasts.
         */
        const val LOCK_TIMEOUT = "set local lock_timeout = '5s'"
        const val COLUMN_CHECKS = """
            select t.relname, a.attname, c.conname, pg_get_constraintdef(c.oid)
            from pg_constraint c
            join pg_class t on t.oid = c.conrelid
            join pg_namespace n on n.oid = t.relnamespace
            join pg_attribute a on a.attrelid = t.oid and a.attnum = c.conkey[1]
            where c.contype = 'c' and cardinality(c.conkey) = 1 and n.nspname = current_schema()"""
        const val TABLE_COLUMNS = """
            select t.relname, a.attname
            from pg_attribute a
            join pg_class t on t.oid = a.attrelid
            join pg_namespace n on n.oid = t.relnamespace
            where n.nspname = current_schema() and t.relkind in ('r', 'p') and a.attnum > 0 and not a.attisdropped"""
        const val NOT_NULL_COLUMNS = "$TABLE_COLUMNS and a.attnotnull"
        const val KEY_COLUMNS = """$TABLE_COLUMNS and (a.attidentity <> '' or exists (select 1 from pg_index i
            where i.indrelid = t.oid and (i.indisprimary or i.indisreplident) and a.attnum = any(i.indkey)))"""
    }
}
