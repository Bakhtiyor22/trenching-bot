package com.trenching.bot.console.automation.domain

/** Day verdict orders by their HTTP [value]; [STATUS] (status rank, then worst-case P&L descending, then newest day) is the default. */
enum class EvaluationSort(val value: String) {
    STATUS("status"),
    DAY_DESC("day,desc");

    companion object {
        const val DEFAULT_VALUE = "status"

        fun of(value: String): EvaluationSort? = entries.firstOrNull { it.value == value }
    }
}
