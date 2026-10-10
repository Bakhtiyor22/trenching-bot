package com.trenching.bot.console.application

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.common.MintAddress
import com.trenching.bot.console.api.dto.ConsoleConfigRequest
import com.trenching.bot.console.domain.ConfigValidation
import com.trenching.bot.console.domain.NumberRange
import com.trenching.bot.console.domain.SimulationConfigLimits
import com.trenching.bot.strategy.domain.SimulationConfig
import java.math.BigDecimal
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

/**
 * Checks every field of a run's configuration and reports each bad one; absent fields take the `mvp()` value and a
 * property that is not a configuration field is reported rather than ignored.
 */
@Component
class ConsoleConfigValidator {
    private val limits = SimulationConfigLimits.CURRENT

    fun validate(request: ConsoleConfigRequest?): ConfigValidation {
        val defaults = SimulationConfig.mvp()
        if (request == null) return ConfigValidation.Valid(defaults)
        val errors = linkedMapOf<String, String>()
        val notional = notional(request.notional, defaults.notional, errors)
        val maxAgeSlots = integer(MAX_AGE_SLOTS, request.maxAgeSlots, defaults.maxAgeSlots, limits.maxAgeSlots, errors)
        val dipBps = integer(DIP_BPS, request.dipBps, defaults.dipBps.toLong(), limits.dipBps, errors)
        val takeProfitBps = integer(TAKE_PROFIT_BPS, request.takeProfitBps, defaults.takeProfitBps.toLong(), limits.takeProfitBps, errors)
        val stopLossBps = integer(STOP_LOSS_BPS, request.stopLossBps, defaults.stopLossBps.toLong(), limits.stopLossBps, errors)
        val minMarketCapUsd = marketCap(request.minMarketCapUsd, defaults.minMarketCapUsd, errors)
        val horizonSlots = integer(HORIZON_SLOTS, request.horizonSlots, defaults.horizonSlots, limits.horizonSlots, errors)
        val latencySlots = integer(LATENCY_SLOTS, request.latencySlots, defaults.latencySlots, limits.latencySlots, errors)
        val transactionCostSol = transactionCost(request.transactionCostSol, defaults.transactionCostSol, errors)
        val quoteMint = quoteMint(request.quoteMint, defaults.quoteMint, errors)
        request.unknownFields.keys.forEach { errors[it] = UNKNOWN_FIELD_MESSAGE }
        if (errors.isNotEmpty()) return ConfigValidation.Invalid(errors)
        return ConfigValidation.Valid(SimulationConfig.of(notional, maxAgeSlots, dipBps.toInt(), takeProfitBps.toInt(),
            stopLossBps.toInt(), minMarketCapUsd, horizonSlots, latencySlots, transactionCostSol, quoteMint = quoteMint))
    }

    private fun notional(node: JsonNode?, default: BigDecimal, errors: MutableMap<String, String>): BigDecimal {
        val value = decimal(NOTIONAL, node, errors) ?: return default
        when {
            value.stripTrailingZeros().scale() > limits.notionalScale ->
                errors[NOTIONAL] = "must be a whole number of lamports: at most ${limits.notionalScale} decimals"
            value.signum() <= 0 -> errors[NOTIONAL] = "must be at least one lamport"
            value.setScale(limits.notionalScale).precision() > limits.notionalPrecision ->
                errors[NOTIONAL] = "must have at most ${limits.notionalPrecision - limits.notionalScale} integer digits"
        }
        return value
    }

    private fun marketCap(node: JsonNode?, default: BigDecimal, errors: MutableMap<String, String>): BigDecimal {
        val scaled = decimal(MIN_MARKET_CAP_USD, node, errors)?.let(DecimalPolicy::scale) ?: return default
        val range = limits.minMarketCapUsd
        if (scaled < BigDecimal.valueOf(range.min) || scaled > BigDecimal.valueOf(requireNotNull(range.max))) {
            errors[MIN_MARKET_CAP_USD] = "must be between ${range.min} and ${range.max} US dollars"
        }
        return scaled
    }

    private fun transactionCost(node: JsonNode?, default: BigDecimal, errors: MutableMap<String, String>): BigDecimal {
        val scaled = decimal(TRANSACTION_COST_SOL, node, errors)?.let(DecimalPolicy::scale) ?: return default
        val range = limits.transactionCostSol
        if (scaled !in range) {
            errors[TRANSACTION_COST_SOL] = "must be between ${range.min.toPlainString()} and ${range.max.toPlainString()} SOL"
        }
        return scaled
    }

    /** A plain decimal string, or null when absent or reported as malformed. */
    private fun decimal(field: String, node: JsonNode?, errors: MutableMap<String, String>): BigDecimal? {
        if (isAbsent(node)) return null
        val text = node?.takeIf { it.isString }?.stringValue()
        if (text == null || !PLAIN_DECIMAL.matches(text)) {
            errors[field] = DECIMAL_FORMAT_MESSAGE
            return null
        }
        return BigDecimal(text)
    }

    private fun integer(field: String, node: JsonNode?, default: Long, range: NumberRange,
                        errors: MutableMap<String, String>): Long {
        if (isAbsent(node)) return default
        val value = node?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()
        if (value == null || value !in range) {
            errors[field] = range.max?.let { "must be an integer between ${range.min} and $it" }
                ?: "must be an integer of at least ${range.min}"
            return default
        }
        return value
    }

    private fun quoteMint(node: JsonNode?, default: MintAddress, errors: MutableMap<String, String>): MintAddress {
        if (isAbsent(node)) return default
        val mint = node?.takeIf { it.isString }?.stringValue()?.let(ConsoleAddresses::mintOrNull)
        if (mint == null) {
            errors[QUOTE_MINT] = "must be a 32-byte base58 address"
            return default
        }
        return mint
    }

    private fun isAbsent(node: JsonNode?): Boolean = node == null || node.isNull || node.isMissingNode

    private companion object {
        const val NOTIONAL = "notional"
        const val MAX_AGE_SLOTS = "maxAgeSlots"
        const val DIP_BPS = "dipBps"
        const val TAKE_PROFIT_BPS = "takeProfitBps"
        const val STOP_LOSS_BPS = "stopLossBps"
        const val MIN_MARKET_CAP_USD = "minMarketCapUsd"
        const val HORIZON_SLOTS = "horizonSlots"
        const val LATENCY_SLOTS = "latencySlots"
        const val TRANSACTION_COST_SOL = "transactionCostSol"
        const val QUOTE_MINT = "quoteMint"
        const val DECIMAL_FORMAT_MESSAGE = "must be a plain decimal string such as \"100\" or \"0.5\""
        const val UNKNOWN_FIELD_MESSAGE = "is not a configuration field"
        val PLAIN_DECIMAL = Regex("[0-9]{1,64}(\\.[0-9]{1,64})?")
    }
}
