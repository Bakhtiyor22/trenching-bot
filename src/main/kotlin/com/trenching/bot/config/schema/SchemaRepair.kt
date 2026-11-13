package com.trenching.bot.config.schema

/**
 * One statement that relaxes a constraint the current entities can violate. [relaxed] names the constraint (or `NOT_NULL`) and
 * [added] what it allows now that it did not before: the enum values a replaced check gained, or `NULL`.
 */
data class SchemaRepair(val table: String, val column: String, val relaxed: String, val sql: String, val added: List<String>)
