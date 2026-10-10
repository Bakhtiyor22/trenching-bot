package com.trenching.bot.replay.infrastructure.fixture

import com.trenching.bot.common.*
import com.trenching.bot.marketdata.ReferenceRate
import com.trenching.bot.marketdata.domain.*
import com.trenching.bot.protocol.pumpfun.CurveReserves
import com.trenching.bot.replay.application.DatasetFingerprint
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.walletactivity.domain.WalletTransaction
import java.io.InputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

class ReplayDatasetLoader(private val mapper: ObjectMapper) {
    fun load(input: InputStream): ReplayDataset {
        val root = mapper.readTree(input)
        val rawVersion = integer(root, "schemaVersion")
        require(rawVersion == ReplayDataset.SCHEMA_VERSION.toLong()) {
            "Unsupported replay schemaVersion=$rawVersion; re-export it with curve state (UNSUPPORTED_SCHEMA)"
        }
        val version = rawVersion.toInt()
        val wallet = WalletAddress.of(string(root, "trackedWallet"))
        val transactions = array(root, "transactions").map { node ->
            val index = integer(node, "instructionIndex")
            require(index in 0..Int.MAX_VALUE) { "Invalid instruction index" }
            WalletTransaction(WalletAddress.of(string(node, "wallet")),
                TransactionSignature.of(string(node, "signature")), index.toInt(),
                Slot.of(integer(node, "slot")), optionalString(node, "blockTime")?.let(::instant),
                pair(node), BigInteger(string(node, "baseDelta")), BigInteger(string(node, "quoteDelta")),
                decimals(node, "baseDecimals"), decimals(node, "quoteDecimals"),
                boolean(node, "successful"), boolean(node, "supportedRoute"), boolean(node, "signer"),
                string(node, "source", ReplayLimits.MAX_SOURCE_LENGTH), Commitment.valueOf(string(node, "commitment")))
        }
        val observations = array(root, "observations").map { node ->
            val price = BigDecimal(string(node, "price"))
            val liquidity = optionalString(node, "liquidityQuote")?.let(::BigDecimal)
            require(price.signum() > 0 && (liquidity == null || liquidity.signum() >= 0)) { "Invalid observation amounts" }
            DecimalPolicy.requireExactPrice(price)
            liquidity?.let(DecimalPolicy::requireExactPrice)
            val id = string(node, "id", ReplayLimits.MAX_OBSERVATION_ID_LENGTH)
            val curveComplete = boolean(node, "curveComplete")
            MarketObservation(id, pair(node), Slot.of(integer(node, "slot")),
                Slot.of(integer(node, "priceSlot")), instant(string(node, "observedTime")), price,
                liquidity, string(node, "source", ReplayLimits.MAX_SOURCE_LENGTH),
                string(node, "sourceEventId", ReplayLimits.MAX_OBSERVATION_ID_LENGTH),
                Commitment.valueOf(string(node, "commitment")), ObservationQuality.valueOf(string(node, "quality")),
                curveComplete, curve(node.path("curve"), id, curveComplete))
        }
        val rates = array(root, "referenceRates").map { node ->
            ReferenceRate(instant(string(node, "minute")), BigDecimal(string(node, "rate")), string(node, "source", ReplayLimits.MAX_SOURCE_LENGTH))
        }
        val ordered = OrderedReferenceRates(rates)
        observations.forEach { observation ->
            require(ordered.rateAt(observation.observedTime) != null) {
                "No reference rate for observation id=${observation.id} minute=${ReferenceRate.minuteOf(observation.observedTime)} (REFERENCE_RATE_MISSING)"
            }
        }
        val dataset = ReplayDataset(version, wallet, transactions, observations, rates)
        val checksum = DatasetFingerprint.checksum(dataset)
        require(string(root, "checksum") == checksum) { "Replay checksum mismatch computed=$checksum" }
        return dataset
    }

    private fun curve(node: JsonNode, observationId: String, curveComplete: Boolean): CurveState {
        val virtualSol = curveInteger(node, ReplayCurveFields.VIRTUAL_SOL_RESERVES, observationId)
        val virtualToken = curveInteger(node, ReplayCurveFields.VIRTUAL_TOKEN_RESERVES, observationId)
        val realSol = curveInteger(node, ReplayCurveFields.REAL_SOL_RESERVES, observationId)
        val realToken = curveInteger(node, ReplayCurveFields.REAL_TOKEN_RESERVES, observationId)
        val feeBps = CurveState.basisPoints(curveInteger(node, ReplayCurveFields.FEE_BPS, observationId))
        val creatorFeeBps = CurveState.basisPoints(curveInteger(node, ReplayCurveFields.CREATOR_FEE_BPS, observationId))
        require(CurveReserves.isValid(virtualSol, virtualToken, realSol, realToken) && feeBps != null && creatorFeeBps != null) {
            "Invalid curve state observation id=$observationId ($CURVE_STATE_INVALID)"
        }
        val curve = CurveState(CurveReserves(virtualSol, virtualToken, realSol, realToken), feeBps, creatorFeeBps)
        require(curve.complete == curveComplete) {
            "curveComplete=$curveComplete contradicts realTokenReserves=$realToken observation id=$observationId (CURVE_STATE_INCONSISTENT)"
        }
        return curve
    }

    private fun curveInteger(node: JsonNode, field: String, observationId: String): BigInteger {
        val value = node.path(field)
        require(value.isString && RAW_INTEGER.matches(value.asString())) {
            "Expected raw integer string curve.$field observation id=$observationId ($CURVE_STATE_INVALID)"
        }
        return BigInteger(value.asString())
    }

    private fun pair(node: JsonNode): MarketPair = MarketPair.of(
        MintAddress.of(string(node, "baseMint")), MintAddress.of(string(node, "quoteMint")))

    /** Rejects what persistence would reject, so offline and persisted replay accept the same datasets. */
    private fun instant(value: String): Instant = Instant.parse(value).also(TimePrecision::requireMicroseconds)

    private fun string(node: JsonNode, field: String, maxLength: Int = Int.MAX_VALUE): String {
        val value = node.path(field)
        require(value.isString && value.asString().isNotBlank()) { "Expected nonempty string field=$field" }
        require(value.asString().length <= maxLength) { "String too long field=$field maxLength=$maxLength" }
        return value.asString()
    }

    private fun optionalString(node: JsonNode, field: String): String? =
        if (node.path(field).isMissingNode || node.path(field).isNull) null else string(node, field)

    private fun integer(node: JsonNode, field: String): Long {
        val value = node.path(field)
        require(value.isIntegralNumber && value.canConvertToLong()) { "Expected integer field=$field" }
        return value.asLong()
    }

    private fun boolean(node: JsonNode, field: String): Boolean {
        val value = node.path(field)
        require(value.isBoolean) { "Expected boolean field=$field" }
        return value.asBoolean()
    }

    private fun decimals(node: JsonNode, field: String): Int {
        val value = integer(node, field)
        require(value in 0..255) { "Invalid decimals field=$field" }
        return value.toInt()
    }

    private fun array(node: JsonNode, field: String): List<JsonNode> {
        val value = node.path(field)
        require(value.isArray && value.size() <= ReplayLimits.MAX_INPUT_RECORDS) { "Expected bounded array field=$field" }
        return value.toList()
    }

    private companion object {
        const val CURVE_STATE_INVALID = "CURVE_STATE_INVALID"
        val RAW_INTEGER = Regex("[0-9]{1,20}")
    }
}
