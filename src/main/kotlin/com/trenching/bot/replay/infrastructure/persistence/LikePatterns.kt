package com.trenching.bot.replay.infrastructure.persistence

/** LIKE patterns for user text. Queries declare `escape '!'`, so `!`, `%` and `_` in the text match themselves. */
object LikePatterns {
    private const val ESCAPE = '!'

    fun prefix(text: String): String = escape(text) + "%"

    fun contains(text: String): String = "%" + escape(text) + "%"

    private fun escape(text: String): String = buildString {
        text.forEach { character ->
            if (character == ESCAPE || character == '%' || character == '_') append(ESCAPE)
            append(character)
        }
    }
}
