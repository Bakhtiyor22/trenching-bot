package com.trenching.bot.console.solana.accounts.infrastructure.helius

import com.trenching.bot.apiusage.ApiCall
import com.trenching.bot.apiusage.ApiCallOutcome
import com.trenching.bot.apiusage.ApiProvider
import com.trenching.bot.apiusage.ApiTask
import com.trenching.bot.apiusage.ApiUsageContext
import com.trenching.bot.apiusage.ApiUsageRecorder
import com.trenching.bot.console.solana.accounts.application.RpcAccountBalances
import com.trenching.bot.console.solana.accounts.domain.BalanceProblem
import com.trenching.bot.console.solana.accounts.domain.BalanceRead
import com.trenching.bot.console.solana.accounts.domain.RpcAccountConfigs
import com.trenching.bot.console.solana.accounts.domain.RpcAccountName
import java.net.URI
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.util.UriUtils
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets

/**
 * Reads an account's credits from the Helius Admin API: one `GET {base-url}/v0/admin/projects/{projectId}/usage` per read, with the
 * account's API key (the `api-key` of its RPC URL) in the `X-Api-Key` header and nowhere else. The project id is part of the request
 * address and the key is a header value, so the message of an exception, which can quote either, is never logged or kept: every failure
 * of a read is classified into a [BalanceProblem] and the exception dropped. A request that was sent is recorded once as `HELIUS_ADMIN` /
 * `projectUsage`, whatever came back; one that could not be built is not, since nothing was sent.
 */
class HeliusUsageClient(
    private val accounts: RpcAccountConfigs,
    baseUrl: String,
    private val restClient: RestClient,
    private val mapper: ObjectMapper,
    private val recorder: ApiUsageRecorder = ApiUsageRecorder.NONE,
) : RpcAccountBalances {
    private val baseUrl = baseUrl.trim().trimEnd('/')

    override fun read(account: RpcAccountName): BalanceRead {
        val config = requireNotNull(accounts[account]) { "RPC account is not configured account=${account.label}" }
        val projectId = config.projectId ?: return BalanceRead.Unavailable(BalanceProblem.NO_PROJECT_ID)
        val apiKey = HeliusApiKeys.fromRpcUrl(config.endpoint) ?: return BalanceRead.Unavailable(BalanceProblem.NO_API_KEY)
        return try {
            restClient.get().uri(usageUri(projectId)).header(API_KEY_HEADER, apiKey).exchange { _, response ->
                val status = response.statusCode.value()
                val body = response.body.readNBytes(MAX_RESPONSE_BYTES)
                val read = if (status in 200..299) parse(body) else BalanceRead.Unavailable(problemOf(status))
                record(outcome(status, read), body.size.toLong())
                read
            } ?: BalanceRead.Unavailable(BalanceProblem.MALFORMED)
        } catch (_: RestClientException) {
            record(ApiCallOutcome.FAILED, 0)
            BalanceRead.Unavailable(BalanceProblem.UNAVAILABLE)
        } catch (_: RuntimeException) {
            BalanceRead.Unavailable(BalanceProblem.UNAVAILABLE)
        }
    }

    private fun usageUri(projectId: String): URI =
        URI.create("$baseUrl/v0/admin/projects/${UriUtils.encodePathSegment(projectId, StandardCharsets.UTF_8)}/usage")

    private fun parse(body: ByteArray): BalanceRead {
        val response: HeliusUsageResponse? = try {
            mapper.readValue(body, HeliusUsageResponse::class.java)
        } catch (_: JacksonException) {
            null
        }
        val balance = response?.let(HeliusUsageMapper::toBalance) ?: return BalanceRead.Unavailable(BalanceProblem.MALFORMED)
        return BalanceRead.Read(balance)
    }

    private fun problemOf(status: Int): BalanceProblem = when (status) {
        STATUS_BAD_REQUEST -> BalanceProblem.INVALID_PROJECT
        STATUS_UNAUTHORIZED -> BalanceProblem.AUTHENTICATION
        STATUS_FORBIDDEN -> BalanceProblem.ADMIN_API_DISABLED
        STATUS_TOO_MANY_REQUESTS -> BalanceProblem.RATE_LIMITED
        else -> BalanceProblem.UNAVAILABLE
    }

    private fun outcome(status: Int, read: BalanceRead): ApiCallOutcome = when {
        status == STATUS_TOO_MANY_REQUESTS -> ApiCallOutcome.RATE_LIMITED
        status in 200..299 && read is BalanceRead.Read -> ApiCallOutcome.OK
        else -> ApiCallOutcome.FAILED
    }

    private fun record(outcome: ApiCallOutcome, responseBytes: Long) =
        recorder.record(ApiCall(ApiProvider.HELIUS_ADMIN, OPERATION, USAGE, outcome, responseBytes))

    companion object {
        const val OPERATION = "projectUsage"
        const val API_KEY_HEADER = "X-Api-Key"
        private const val MAX_RESPONSE_BYTES = 64 * 1024
        private const val STATUS_BAD_REQUEST = 400
        private const val STATUS_UNAUTHORIZED = 401
        private const val STATUS_FORBIDDEN = 403
        private const val STATUS_TOO_MANY_REQUESTS = 429
        private val USAGE = ApiUsageContext(ApiTask.ACCOUNT_BALANCE, null)
    }
}
