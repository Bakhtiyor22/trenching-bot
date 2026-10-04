package com.trenching.bot.chaincapture

import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import java.math.BigInteger
import tools.jackson.databind.JsonNode

object ParsedTransactionStructure {
    private const val MAX_ACCOUNT_KEYS = 256
    private const val MAX_U8 = 255
    private val MAX_U64: BigInteger = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
    private val DECIMAL_DIGITS = Regex("[0-9]{1,20}")
    private val RESULT_FIELDS = setOf("slot", "blockTime", "meta", "transaction", "version", "transactionIndex")
    private val TRANSACTION_FIELDS = setOf("message", "signatures")
    private val MESSAGE_FIELDS = setOf("accountKeys", "instructions", "recentBlockhash", "addressTableLookups", "transactionConfig")
    private val TRANSACTION_CONFIG_FIELDS = setOf("priorityFee", "computeUnitLimit", "loadedAccountsDataSizeLimit", "heapSize")
    private val META_FIELDS = setOf("err", "status", "fee", "preBalances", "postBalances", "preTokenBalances", "postTokenBalances",
        "innerInstructions", "logMessages", "rewards", "loadedAddresses", "returnData", "computeUnitsConsumed", "costUnits")

    fun isValid(result: JsonNode): Boolean {
        val transaction = result.path("transaction")
        val message = transaction.path("message")
        val meta = result.path("meta")
        return onlyFields(result, RESULT_FIELDS) && onlyFields(transaction, TRANSACTION_FIELDS) &&
            onlyFields(message, MESSAGE_FIELDS) && onlyFields(meta, META_FIELDS) &&
            nonNegativeLong(result.path("slot")) && result.has("blockTime") && optional(result.path("blockTime"), ::long) &&
            optional(result.path("transactionIndex"), ::nonNegativeLong) &&
            nonEmptyArray(transaction.path("signatures"), Int.MAX_VALUE, ::signature) &&
            validMessage(message) && validMeta(meta)
    }

    private fun validMessage(message: JsonNode): Boolean =
        nonEmptyArray(message.path("accountKeys"), MAX_ACCOUNT_KEYS, ::accountKey) &&
            array(message.path("instructions"), ::instruction) &&
            message.path("recentBlockhash").isString &&
            optional(message.path("addressTableLookups")) { array(it, JsonNode::isObject) } &&
            optional(message.path("transactionConfig"), ::transactionConfig)

    private fun validMeta(meta: JsonNode): Boolean {
        val preBalances = meta.path("preBalances")
        val postBalances = meta.path("postBalances")
        val err = meta.path("err")
        return meta.has("err") && (err.isNull || err.isString || err.isObject) &&
            nonNegativeIntegral(meta.path("fee")) &&
            array(preBalances, ::nonNegativeIntegral) && array(postBalances, ::nonNegativeIntegral) &&
            preBalances.size() == postBalances.size() &&
            optional(meta.path("status"), JsonNode::isObject) &&
            optional(meta.path("innerInstructions")) { array(it, ::innerInstructions) } &&
            optional(meta.path("logMessages")) { array(it, JsonNode::isString) } &&
            optional(meta.path("preTokenBalances")) { array(it, ::tokenBalance) } &&
            optional(meta.path("postTokenBalances")) { array(it, ::tokenBalance) } &&
            optional(meta.path("rewards"), JsonNode::isArray) &&
            optional(meta.path("loadedAddresses"), ::loadedAddresses) &&
            optional(meta.path("returnData"), JsonNode::isObject) &&
            optional(meta.path("computeUnitsConsumed"), ::nonNegativeIntegral) &&
            optional(meta.path("costUnits"), ::nonNegativeIntegral)
    }

    private fun accountKey(key: JsonNode): Boolean = key.isObject && address(key.path("pubkey")) && key.path("signer").isBoolean &&
        optional(key.path("writable"), JsonNode::isBoolean) && optional(key.path("source"), JsonNode::isString)

    private fun instruction(instruction: JsonNode): Boolean = instruction.isObject && address(instruction.path("programId"))

    private fun transactionConfig(config: JsonNode): Boolean = onlyFields(config, TRANSACTION_CONFIG_FIELDS) &&
        config.properties().all { optional(it.value, ::nonNegativeIntegral) }

    private fun innerInstructions(entry: JsonNode): Boolean =
        entry.isObject && entry.path("index").isIntegralNumber && array(entry.path("instructions"), ::instruction)

    private fun tokenBalance(balance: JsonNode): Boolean {
        val amount = balance.path("uiTokenAmount")
        return balance.isObject && u8(balance.path("accountIndex")) && address(balance.path("mint")) &&
            amount.isObject && u64String(amount.path("amount")) && u8(amount.path("decimals")) &&
            optional(balance.path("owner"), JsonNode::isString) && optional(balance.path("programId"), JsonNode::isString)
    }

    private fun loadedAddresses(addresses: JsonNode): Boolean =
        addresses.isObject && addresses.properties().all { array(it.value, JsonNode::isString) }

    private fun onlyFields(node: JsonNode, allowed: Set<String>): Boolean = node.isObject && node.properties().all { it.key in allowed }

    private fun array(node: JsonNode, element: (JsonNode) -> Boolean): Boolean = node.isArray && node.all(element)

    private fun nonEmptyArray(node: JsonNode, maximum: Int, element: (JsonNode) -> Boolean): Boolean =
        array(node, element) && node.size() in 1..maximum

    private fun optional(node: JsonNode, check: (JsonNode) -> Boolean): Boolean = node.isMissingNode || node.isNull || check(node)

    private fun long(node: JsonNode): Boolean = node.isIntegralNumber && node.canConvertToLong()

    private fun nonNegativeLong(node: JsonNode): Boolean = long(node) && node.asLong() >= 0

    private fun nonNegativeIntegral(node: JsonNode): Boolean = node.isIntegralNumber && node.bigIntegerValue().signum() >= 0

    private fun u8(node: JsonNode): Boolean = node.isIntegralNumber && node.canConvertToInt() && node.asInt() in 0..MAX_U8

    private fun u64String(node: JsonNode): Boolean =
        node.isString && DECIMAL_DIGITS.matches(node.asString()) && BigInteger(node.asString()) <= MAX_U64

    private fun address(node: JsonNode): Boolean = node.isString && try {
        WalletAddress.of(node.asString())
        true
    } catch (_: IllegalArgumentException) { false }

    private fun signature(node: JsonNode): Boolean = node.isString && try {
        TransactionSignature.of(node.asString())
        true
    } catch (_: IllegalArgumentException) { false }
}
