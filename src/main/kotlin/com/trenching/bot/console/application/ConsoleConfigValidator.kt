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

/** Checks every field of a run's configuration and reports each bad one; absent fields take the `mvp()` value. */
@Component
class ConsoleConfigValidator {
    private val limits = SimulationConfigLimits.CURRENT

    fun validate(request: ConsoleConfigRequest?): ConfigValidation {
        val defaults = SimulationConfig.mvp()
        if (request == null) return ConfigValidation.Valid(defaults)
        val errors = linkedMapOf<String, String>()
        val notional = notional(request.notional, defaults.notional, errors)
        val feeBps = integer(FEE_BPS, request.feeBps, defaults.feeBps.toLong(), limits.feeBps, errors)
        val slippageBps = integer(SLIPPAGE_BPS, request.slippageBps, defaults.slippageBps.toLong(), limits.slippageBps, errors)
        val maxWaitSlots = integer(MAX_WAIT_SLOTS, request.maxWaitSlots, defaults.maxWaitSlots, limits.maxWaitSlots, errors)
        val maxAgeSlots = integer(MAX_AGE_SLOTS, request.maxAgeSlots, defaults.maxAgeSlots, limits.maxAgeSlots, errors)
        val holdingSlots = integer(HOLDING_SLOTS, request.holdingSlots, defaults.holdingSlots, limits.holdingSlots, errors)
        val quoteMint = quoteMint(request.quoteMint, defaults.quoteMint, errors)
        if (errors.isNotEmpty()) return ConfigValidation.Invalid(errors)
        return ConfigValidation.Valid(SimulationConfig.of(notional, feeBps.toInt(), slippageBps.toInt(), maxWaitSlots,
            maxAgeSlots, holdingSlots, quoteMint = quoteMint))
    }

    private fun notional(node: JsonNode?, default: BigDecimal, errors: MutableMap<String, String>): BigDecimal {
        if (isAbsent(node)) return default
        val text = node?.takeIf { it.isString }?.stringValue()
        if (text == null || !PLAIN_DECIMAL.matches(text)) {
            errors[NOTIONAL] = NOTIONAL_FORMAT_MESSAGE
            return default
        }
        val scaled = DecimalPolicy.scale(BigDecimal(text))
        when {
            scaled.signum() <= 0 -> errors[NOTIONAL] = "must be positive at scale ${limits.notionalScale}"
            scaled.precision() > limits.notionalPrecision ->
                errors[NOTIONAL] = "must have at most ${limits.notionalPrecision - limits.notionalScale} integer digits"
        }
        return scaled
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
        const val FEE_BPS = "feeBps"
        const val SLIPPAGE_BPS = "slippageBps"
        const val MAX_WAIT_SLOTS = "maxWaitSlots"
        const val MAX_AGE_SLOTS = "maxAgeSlots"
        const val HOLDING_SLOTS = "holdingSlots"
        const val QUOTE_MINT = "quoteMint"
        const val NOTIONAL_FORMAT_MESSAGE = "must be a positive plain decimal string such as \"100\" or \"0.5\""
        val PLAIN_DECIMAL = Regex("[0-9]{1,64}(\\.[0-9]{1,64})?")
    }
}
