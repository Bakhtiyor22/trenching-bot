package com.trenching.bot.replay.api

internal sealed interface CommandArguments<out T> {
    data class Valid<T>(val value: T) : CommandArguments<T>
    data object Invalid : CommandArguments<Nothing>
}
