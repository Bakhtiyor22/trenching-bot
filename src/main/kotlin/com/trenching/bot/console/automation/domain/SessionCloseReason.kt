package com.trenching.bot.console.automation.domain

/** Why a session closed; [meaning] is the lower-case clause, with no full stop, that says so in a log sentence. */
enum class SessionCloseReason(val meaning: String) {
    IDLE("no new buy arrived within the idle time"),
    MANUAL("a person closed it from the console"),
}
