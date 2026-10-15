package com.trenching.bot.console.application

import com.trenching.bot.common.MarketPair
import com.trenching.bot.console.domain.InvalidConsoleRequestException

/** Parses the `pair` request parameter shared by the dataset and chart endpoints. */
object ConsolePairs {
    const val PAIR = "pair"
    private const val SEPARATOR = "/"

    /** [value] is `<baseMint>/<quoteMint>`. */
    fun parse(value: String): MarketPair {
        val mints = value.split(SEPARATOR).map(ConsoleAddresses::mintOrNull)
        val base = mints.getOrNull(0)
        val quote = mints.getOrNull(1)
        if (mints.size != 2 || base == null || quote == null || base == quote) {
            throw InvalidConsoleRequestException(PAIR, "must be <baseMint>/<quoteMint> with two different base58 mints")
        }
        return MarketPair.of(base, quote)
    }
}
