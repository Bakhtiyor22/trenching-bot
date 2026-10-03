package com.trenching.bot.console.solana

/** The `getTransaction` (jsonParsed) fields the wallet normalizer needs, named by their JSON path. */
enum class NormalizerField(val path: String) {
    SLOT("slot"),
    BLOCK_TIME("blockTime"),
    META_ERR("meta.err"),
    SIGNER_FLAGS("transaction.message.accountKeys[].signer"),
    POST_TOKEN_BALANCE_MINT("meta.postTokenBalances[].mint"),
    POST_TOKEN_BALANCE_OWNER("meta.postTokenBalances[].owner"),
    POST_TOKEN_BALANCE_AMOUNT("meta.postTokenBalances[].uiTokenAmount.amount"),
    POST_TOKEN_BALANCE_DECIMALS("meta.postTokenBalances[].uiTokenAmount.decimals"),
}
