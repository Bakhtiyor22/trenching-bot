package com.trenching.bot.console.application

import com.trenching.bot.console.domain.InvalidConsoleRequestException

/** Rejects page arguments the replay ports would refuse; the ports cap the page size at 100. */
object ConsolePaging {
    fun require(page: Int, size: Int) {
        if (page < 0) throw InvalidConsoleRequestException("page", "must not be negative")
        if (size <= 0) throw InvalidConsoleRequestException("size", "must be positive")
    }
}
