package com.trenching.bot.console.application

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.common.MintAddress
import com.trenching.bot.console.api.dto.ConsoleConfigRequest
import com.trenching.bot.console.domain.ConfigErrorCode
import com.trenching.bot.console.domain.ConfigValidation
import com.trenching.bot.console.domain.NumberRange
import com.trenching.bot.console.domain.PercentConversion
import com.trenching.bot.console.domain.PercentToBps
import com.trenching.bot.console.domain.SimulationConfigLimits
import com.trenching.bot.strategy.domain.SimulationConfig
import java.math.BigDecimal
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

/**
 * Checks every field of a run's configuration and reports each bad one; absent fields take the `mvp()` value and a
 * property that is not a configuration field is reported rather than ignored. A level sent as a percent is converted to
 * exact basis points here, before the run starts, so the stored and hashed configuration never sees the percent.
 */
@Component
class ConsoleConfigValidator {
    private val limits = SimulationConfigLimits.CURRENT

    fun validate(request: ConsoleConfigRequest?): ConfigValidation {
        val defaults = SimulationConfig.mvp()
        if (request == null) return ConfigValidation.Valid(defaults)
        val errors = linkedMapOf<String, String>()
        val conflicts = mutableSetOf<String>()
        val notional = notional(request.notional, defaults.notional, errors)
        val maxAgeSlots = integer(MAX_AGE_SLOTS, request.maxAgeSlots, defaults.maxAgeSlots, limits.maxAgeSlots, errors)
        val dipBps = level(DIP_BPS, request.dipBps, DIP_PERCENT, request.dipPercent, defaults.dipBps, limits.dipBps, errors, conflicts)
        val takeProfitBps = level(TAKE_PROFIT_BPS, request.takeProfitBps, TAKE_PROFIT_PERCENT, request.takeProfitPercent,
            defaults.takeProfitBps, limits.takeProfitBps, errors, conflicts)
        val stopLossBps = level(STOP_LOSS_BPS, request.stopLossBps, STOP_LOSS_PERCENT, request.stopLossPercent, defaults.stopLossBps,
            limits.stopLossBps, errors, conflicts)
        val minMarketCapUsd = marketCap(request.minMarketCapUsd, defaults.minMarketCapUsd, errors)
        val horizonSlots = integer(HORIZON_SLOTS, request.horizonSlots, defaults.horizonSlots, limits.horizonSlots, errors)
        val latencySlots = integer(LATENCY_SLOTS, request.latencySlots, defaults.latencySlots, limits.latencySlots, errors)
        val transactionCostSol = transactionCost(request.transactionCostSol, defaults.transactionCostSol, errors)
        val quoteMint = quoteMint(request.quoteMint, defaults.quoteMint, errors)
        request.unknownFields.keys.forEach { errors[it] = UNKNOWN_FIELD_MESSAGE }
        if (errors.isNotEmpty()) return ConfigValidation.Invalid(errors, errorCode(errors.keys, conflicts))
        return ConfigValidation.Valid(SimulationConfig.of(notional, maxAgeSlots, dipBps, takeProfitBps, stopLossBps, minMarketCapUsd,
            horizonSlots, latencySlots, transactionCostSol, quoteMint = quoteMint))
    }

    private fun errorCode(fields: Set<String>, conflicts: Set<String>): ConfigErrorCode = when {
        conflicts.isNotEmpty() -> ConfigErrorCode.CONFLICTING_FIELDS
        fields.any { it in PERCENT_FIELDS } -> ConfigErrorCode.INVALID_PERCENT
        else -> ConfigErrorCode.CONFIG_INVALID
    }

    /** A level from its basis points or its percent twin; a conflict is only reported when both are valid on their own. */
    private fun level(bpsField: String, bpsNode: JsonNode?, percentField: String, percentNode: JsonNode?, default: Int,
                      range: NumberRange, errors: MutableMap<String, String>, conflicts: MutableSet<String>): Int {
        val bps = integerOrNull(bpsField, bpsNode, range, errors)?.toInt()
        val percent = percent(percentField, percentNode, range, errors)
        if (bps != null && percent != null && bps != percent) {
            errors[percentField] = "must equal $bpsField when both are sent: $bpsField $bps is ${PercentConversion.toPercent(bps.toLong())} percent"
            conflicts += percentField
        }
        return percent ?: bps ?: default
    }

    private fun percent(field: String, node: JsonNode?, range: NumberRange, errors: MutableMap<String, String>): Int? {
        if (isAbsent(node)) return null
        val text = node?.takeIf { it.isString }?.stringValue() ?: return reject(field, PERCENT_FORMAT_MESSAGE, errors)
        return when (val converted = PercentConversion.toBps(text, range)) {
            is PercentToBps.Converted -> converted.bps
            PercentToBps.Malformed -> reject(field, PERCENT_FORMAT_MESSAGE, errors)
            is PercentToBps.OutOfRange -> reject(field, percentRangeMessage(converted.range), errors)
        }
    }

    private fun percentRangeMessage(range: NumberRange): String {
        val min = PercentConversion.toPercent(range.min)
        return range.max?.let { "must be between $min and ${PercentConversion.toPercent(it)} percent" } ?: "must be at least $min percent"
    }

    private fun reject(field: String, message: String, errors: MutableMap<String, String>): Int? {
        errors[field] = message
        return null
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
                        errors: MutableMap<String, String>): Long = integerOrNull(field, node, range, errors) ?: default

    /** Null when absent or reported as invalid. */
    private fun integerOrNull(field: String, node: JsonNode?, range: NumberRange, errors: MutableMap<String, String>): Long? {
        if (isAbsent(node)) return null
        val value = node?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()
        if (value == null || value !in range) {
            errors[field] = range.max?.let { "must be an integer between ${range.min} and $it" }
                ?: "must be an integer of at least ${range.min}"
            return null
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
        const val DIP_PERCENT = "dipPercent"
        const val TAKE_PROFIT_PERCENT = "takeProfitPercent"
        const val STOP_LOSS_PERCENT = "stopLossPercent"
        const val MIN_MARKET_CAP_USD = "minMarketCapUsd"
        const val HORIZON_SLOTS = "horizonSlots"
        const val LATENCY_SLOTS = "latencySlots"
        const val TRANSACTION_COST_SOL = "transactionCostSol"
        const val QUOTE_MINT = "quoteMint"
        const val DECIMAL_FORMAT_MESSAGE = "must be a plain decimal string such as \"100\" or \"0.5\""
        const val UNKNOWN_FIELD_MESSAGE = "is not a configuration field"
        const val PERCENT_FORMAT_MESSAGE = "must be a percent string with at most 2 decimals, such as \"20\" or \"15.5\""
        val PERCENT_FIELDS = setOf(DIP_PERCENT, TAKE_PROFIT_PERCENT, STOP_LOSS_PERCENT)
        val PLAIN_DECIMAL = Regex("[0-9]{1,64}(\\.[0-9]{1,64})?")
    }
}
