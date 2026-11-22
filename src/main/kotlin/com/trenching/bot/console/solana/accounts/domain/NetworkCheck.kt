package com.trenching.bot.console.solana.accounts.domain

/** The outcome of asking an account's endpoint for its genesis hash: [Mainnet], another network, or no answer that says (timeout, provider failure). */
sealed interface NetworkCheck {
    data object Mainnet : NetworkCheck

    data object OtherNetwork : NetworkCheck

    data object Unknown : NetworkCheck
}
