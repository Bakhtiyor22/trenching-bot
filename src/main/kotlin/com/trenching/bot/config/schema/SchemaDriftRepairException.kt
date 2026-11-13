package com.trenching.bot.config.schema

import java.sql.SQLException

/** A stale constraint could not be relaxed, so the application must not start writing against it. */
class SchemaDriftRepairException(repair: SchemaRepair, cause: SQLException) : RuntimeException(
    "schema drift repair failed table=${repair.table} column=${repair.column} relaxed=${repair.relaxed}" +
        if (cause.sqlState == LOCK_NOT_AVAILABLE) " reason=$TABLE_LOCKED" else "",
    cause,
) {
    companion object {
        /** Another session held a lock on the table past the repair's lock timeout; the start can be retried once it is idle. */
        const val TABLE_LOCKED = "TABLE_LOCKED_BY_ANOTHER_SESSION"
        private const val LOCK_NOT_AVAILABLE = "55P03"
    }
}
