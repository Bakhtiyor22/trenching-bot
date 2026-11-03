package com.trenching.bot.console.automation.domain

/** Scoreboard orders by their HTTP [value]; [STATUS] (status rank, then worst-case P&L descending, then newest) is the default. */
enum class EvaluationSort(val value: String) {
    STATUS("status"),
    EVALUATED_DESC("evaluatedAt,desc");

    companion object {
        const val DEFAULT_VALUE = "status"

        fun of(value: String): EvaluationSort? = entries.firstOrNull { it.value == value }
    }
}
