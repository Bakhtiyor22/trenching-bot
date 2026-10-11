package com.trenching.bot.walletactivity

/** Which address's signature listing discovery scans for the wallet's history. */
enum class WalletHistoryScope {
    /** The wallet itself: every transaction that mentions it, including other accounts' bots. */
    WALLET_REFERENCES,

    /** The wallet's Pump.fun user volume accumulator, which Pump.fun's `buy` passes for its user. */
    PUMPFUN_USER_VOLUME_ACCUMULATOR,

    /**
     * The wallet's associated token account for one target mint, under the token program that owns the mint. It finds the
     * wallet's earliest recognised buy of that mint through the account.
     */
    WALLET_TOKEN_ACCOUNT,
}
