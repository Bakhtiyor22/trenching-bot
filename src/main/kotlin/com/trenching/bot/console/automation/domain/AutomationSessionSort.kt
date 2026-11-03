package com.trenching.bot.console.automation.domain

/** Session list orders by their HTTP [value]; [STATUS] is the default. */
enum class AutomationSessionSort(val value: String) {
    STATUS("status"),
    CREATED_DESC("createdAt,desc");

    companion object {
        const val DEFAULT_VALUE = "status"

        fun of(value: String): AutomationSessionSort? = entries.firstOrNull { it.value == value }
    }
}
