package com.trenching.bot.console.application

import com.trenching.bot.console.domain.InvalidConsoleRequestException

/** The free-text search `q` of the run and dataset lists: 2 to 100 characters once trimmed. */
object ConsoleSearchText {
    private const val FIELD = "q"
    private const val MIN_LENGTH = 2
    private const val MAX_LENGTH = 100

    fun validated(value: String): String {
        val text = value.trim()
        if (text.length !in MIN_LENGTH..MAX_LENGTH) {
            throw InvalidConsoleRequestException(FIELD, "must be $MIN_LENGTH to $MAX_LENGTH characters")
        }
        return text
    }
}
