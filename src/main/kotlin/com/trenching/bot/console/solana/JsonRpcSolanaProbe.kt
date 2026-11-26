package com.trenching.bot.console.solana

import com.trenching.bot.apiusage.ApiUsageRecorder
import com.trenching.bot.chaincapture.RpcEndpointChoice
import com.trenching.bot.chaincapture.RpcEndpointSource
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.console.solana.ProbeOutcome.Unavailable.Companion.MALFORMED
import com.trenching.bot.console.solana.dto.SignatureInfo
import com.trenching.bot.console.solana.dto.SolanaVersion
import org.springframework.web.client.RestClient
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Checks the account [endpoints] names as active at the moment of each call, so a probe follows a failover. With no account configured the
 * outcome is [ProbeOutcome.NotConfigured]; with every account out of credits it is unavailable, and [host] falls back to [primaryHost].
 */
class JsonRpcSolanaProbe(
    private val restClient: RestClient,
    private val objectMapper: ObjectMapper,
    private val endpoints: RpcEndpointSource,
    private val recorder: ApiUsageRecorder = ApiUsageRecorder.NONE,
    private val primaryHost: String? = null,
) : SolanaProbe {
    /** One fixed endpoint, which never runs out of credits. */
    constructor(
        endpoint: SolanaRpcEndpoint?,
        restClient: RestClient,
        objectMapper: ObjectMapper,
        recorder: ApiUsageRecorder = ApiUsageRecorder.NONE,
    ) : this(restClient, objectMapper, RpcEndpointSource.fixed(endpoint?.uri?.toString()), recorder, endpoint?.host)

    override val host: String?
        get() = when (val choice = endpoints.choose()) {
            is RpcEndpointChoice.Use -> SolanaRpcEndpoint.from(choice.target.endpoint)?.host
            RpcEndpointChoice.NotConfigured -> null
            RpcEndpointChoice.OutOfCredits -> primaryHost
        }

    override fun check(): ProbeOutcome {
        val client = when (val target = target()) {
            is Target.Ready -> target.client
            is Target.Unusable -> return target.outcome
        }
        val started = System.nanoTime()
        val health = client.call(SolanaRpc.GET_HEALTH).resultOr { return it }
        val latencyMs = (System.nanoTime() - started) / NANOS_PER_MILLI
        if (health.asString() != SolanaRpc.HEALTHY) return MALFORMED
        val version = client.call(SolanaRpc.GET_VERSION).resultOr { return it }
            .let { read(it, SolanaVersion::class.java) ?: return MALFORMED }
        val slot = client.call(SolanaRpc.GET_SLOT, listOf(confirmed())).resultOr { return it }
            .takeIf { it.isIntegralNumber } ?: return MALFORMED
        val genesisHash = client.call(SolanaRpc.GET_GENESIS_HASH).resultOr { return it }
            .takeIf { it.isString } ?: return MALFORMED
        return ProbeOutcome.Ok(latencyMs, version.solanaCore, slot.asLong(), SolanaCluster.fromGenesisHash(genesisHash.asString()))
    }

    override fun checkAddress(address: WalletAddress): AddressCheckOutcome {
        val client = when (val target = target()) {
            is Target.Ready -> target.client
            is Target.Unusable -> return AddressCheckOutcome.Failed(target.outcome)
        }
        val signatures = client.call(SolanaRpc.GET_SIGNATURES_FOR_ADDRESS, listOf(address.value, signatureQuery()))
            .resultOr { return AddressCheckOutcome.Failed(it) }
            .let { read(it, Array<SignatureInfo>::class.java) ?: return AddressCheckOutcome.Failed(MALFORMED) }
        val newestSuccessful = signatures.firstOrNull { it.err == null || it.err.isNull }
            ?: return AddressCheckOutcome.NoSuccessfulTransaction(signatures.size)
        val transaction = client.call(SolanaRpc.GET_TRANSACTION, listOf(newestSuccessful.signature, transactionQuery()))
            .resultOr { return AddressCheckOutcome.Failed(it) }
        val present = NormalizerFieldInspector.presentFields(transaction)
        return AddressCheckOutcome.Checked(
            signature = newestSuccessful.signature,
            slot = newestSuccessful.slot,
            transactionVersion = transaction.path("version").takeIf { it.isValueNode }?.asString(),
            presentFields = present,
            missingFields = NormalizerField.entries - present.toSet(),
        )
    }

    private fun target(): Target = when (val choice = endpoints.choose()) {
        is RpcEndpointChoice.Use -> SolanaRpcEndpoint.from(choice.target.endpoint)
            ?.let { Target.Ready(SolanaRpcClient(restClient, it, objectMapper, recorder)) } ?: Target.Unusable(ProbeOutcome.NotConfigured)
        RpcEndpointChoice.NotConfigured -> Target.Unusable(ProbeOutcome.NotConfigured)
        RpcEndpointChoice.OutOfCredits -> Target.Unusable(NO_CREDITS)
    }

    private sealed interface Target {
        class Ready(val client: SolanaRpcClient) : Target

        class Unusable(val outcome: ProbeOutcome.Failure) : Target
    }

    private inline fun RpcReply.resultOr(onFailure: (ProbeOutcome.Failure) -> Nothing): JsonNode = when (this) {
        is RpcReply.Success -> result ?: onFailure(MISSING_RESULT)
        is RpcReply.Failed -> onFailure(failure)
    }

    private fun <T : Any> read(node: JsonNode, type: Class<T>): T? =
        try {
            objectMapper.treeToValue(node, type)
        } catch (_: JacksonException) {
            null
        }

    private fun confirmed() = mapOf(SolanaRpc.COMMITMENT to SolanaRpc.CONFIRMED)

    private fun signatureQuery() = confirmed() + ("limit" to SolanaRpc.SIGNATURE_LIMIT)

    private fun transactionQuery() = confirmed() + mapOf(
        "encoding" to "jsonParsed",
        "maxSupportedTransactionVersion" to SolanaRpc.MAX_SUPPORTED_TRANSACTION_VERSION,
    )

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        val MISSING_RESULT = ProbeOutcome.Unavailable("empty result")
        val NO_CREDITS = ProbeOutcome.Unavailable("no RPC account has credits")
    }
}
