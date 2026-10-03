package com.trenching.bot.console.solana

import tools.jackson.databind.JsonNode

/** Reports which [NormalizerField]s a `getTransaction` (jsonParsed) result carries. A field is present only if every entry has it. */
object NormalizerFieldInspector {
    fun presentFields(transaction: JsonNode): List<NormalizerField> =
        NormalizerField.entries.filter { isPresent(transaction, it) }

    private fun isPresent(transaction: JsonNode, field: NormalizerField): Boolean {
        val balances = transaction.path("meta").path("postTokenBalances")
        return when (field) {
            NormalizerField.SLOT -> transaction.path("slot").isIntegralNumber
            NormalizerField.BLOCK_TIME -> transaction.path("blockTime").isIntegralNumber
            NormalizerField.META_ERR -> transaction.path("meta").let { it.isObject && it.has("err") }
            NormalizerField.SIGNER_FLAGS -> everyEntry(transaction.path("transaction").path("message").path("accountKeys")) {
                it.path("signer").isBoolean
            }
            NormalizerField.POST_TOKEN_BALANCE_MINT -> everyEntry(balances) { it.path("mint").isString }
            NormalizerField.POST_TOKEN_BALANCE_OWNER -> everyEntry(balances) { it.path("owner").isString }
            NormalizerField.POST_TOKEN_BALANCE_AMOUNT -> everyEntry(balances) { it.path("uiTokenAmount").path("amount").isString }
            NormalizerField.POST_TOKEN_BALANCE_DECIMALS -> everyEntry(balances) { it.path("uiTokenAmount").path("decimals").isIntegralNumber }
        }
    }

    private fun everyEntry(array: JsonNode, predicate: (JsonNode) -> Boolean): Boolean =
        array.isArray && !array.isEmpty && array.all(predicate)
}
