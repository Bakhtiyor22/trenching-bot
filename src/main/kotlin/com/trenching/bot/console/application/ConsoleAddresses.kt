package com.trenching.bot.console.application

import com.trenching.bot.common.MintAddress

/** Parses user-supplied mints; `MintAddress.of` only offers a throwing check. */
object ConsoleAddresses {
    fun mintOrNull(value: String): MintAddress? = try {
        MintAddress.of(value)
    } catch (_: IllegalArgumentException) {
        null
    }
}
