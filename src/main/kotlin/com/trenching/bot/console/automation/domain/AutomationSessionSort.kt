package com.trenching.bot.console.automation.domain

/**
 * Session list orders by their HTTP [value]; [CREATED_DESC] is the default. The `status` order the session evaluations once gave
 * is gone; it is still accepted as an alias of the default so a page that sends it keeps working until it is updated.
 */
enum class AutomationSessionSort(val value: String) {
    CREATED_DESC("createdAt,desc");

    companion object {
        const val DEFAULT_VALUE = "createdAt,desc"

        /** The removed order by evaluation status, now the default order. */
        const val DEPRECATED_STATUS = "status"

        fun of(value: String): AutomationSessionSort? =
            entries.firstOrNull { it.value == value } ?: CREATED_DESC.takeIf { value == DEPRECATED_STATUS }
    }
}
