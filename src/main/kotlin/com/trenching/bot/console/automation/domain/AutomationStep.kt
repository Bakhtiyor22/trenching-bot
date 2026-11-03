package com.trenching.bot.console.automation.domain

/** The steps of an item's pipeline (plan Rule 13): capture runs [CREATE_SESSION] through [REGISTER], then the [SWEEP]. */
enum class AutomationStep {
    CREATE_SESSION,
    WALLET,
    MARKET,
    RATES,
    EXPORT,
    QUALIFY,
    REGISTER,
    SWEEP;

    fun next(): AutomationStep = when (this) {
        CREATE_SESSION -> WALLET
        WALLET -> MARKET
        MARKET -> RATES
        RATES -> EXPORT
        EXPORT -> QUALIFY
        QUALIFY -> REGISTER
        REGISTER -> SWEEP
        SWEEP -> throw IllegalStateException("The sweep is an item's last step")
    }
}
