package com.trenching.bot.config.schema

/**
 * A column as the current entities map it. [membershipCheck] is the condition Hibernate generates for an enum column, such as
 * `watch in ('TRIGGERED','NO_DIP')`, and null for a column it generates none for.
 */
data class MappedColumn(val table: String, val column: String, val nullable: Boolean, val membershipCheck: String?)
