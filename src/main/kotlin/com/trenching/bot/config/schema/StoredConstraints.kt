package com.trenching.bot.config.schema

data class ColumnId(val table: String, val column: String)

/** A check constraint over exactly one column, with its definition as PostgreSQL's `pg_get_constraintdef` prints it. */
data class StoredColumnCheck(val table: String, val column: String, val name: String, val definition: String)

/**
 * What a database enforces today: its single-column checks, its NOT NULL columns, and the [keyColumns] whose NOT NULL is
 * structural (primary key, replica identity or identity column) and cannot be dropped.
 */
data class StoredConstraints(
    val columnChecks: List<StoredColumnCheck>,
    val notNullColumns: Set<ColumnId>,
    val keyColumns: Set<ColumnId> = emptySet(),
)
