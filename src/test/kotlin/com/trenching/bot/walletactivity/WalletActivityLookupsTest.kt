package com.trenching.bot.walletactivity

import com.sun.net.httpserver.HttpExchange
import com.trenching.bot.apiusage.ApiTask
import com.trenching.bot.apiusage.ApiUsageContext
import com.trenching.bot.apiusage.ApiUsageRecorder
import com.trenching.bot.chaincapture.CaptureBudget
import com.trenching.bot.chaincapture.ChainCaptureFormat
import com.trenching.bot.chaincapture.LocalRpcServer
import com.trenching.bot.chaincapture.RpcLimiterRegistry
import com.trenching.bot.chaincapture.TestSettings
import com.trenching.bot.chaincapture.testClock
import com.trenching.bot.walletactivity.WalletActivityFixtures.DECU
import com.trenching.bot.walletactivity.WalletActivityFixtures.SELL_SIGNATURE
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/** The lookup as production builds it, over a real HTTP transport against a JSON-RPC stub on 127.0.0.1. */
class WalletActivityLookupsTest {
    private val json = JsonMapper.builder().addModule(kotlinModule()).build()
    private val usage = ApiUsageContext(ApiTask.WALLET_ACTIVITY, null)
    private val requests = ConcurrentLinkedQueue<Pair<String, String?>>()

    @Test
    fun `a rate limited read inside a full page is PROVIDER_RATE_LIMITED with its Retry-After, after one request for that read`() {
        val result = LocalRpcServer(provider(rateLimited = SELL_SIGNATURE)).use { server ->
            lookup(server).recent(WalletActivityRequest(DECU, FULL_PAGE_LIMIT, null), usage)
        }

        assertEquals(WalletActivityResult.Unavailable(WalletActivityUnavailable.PROVIDER_RATE_LIMITED, Duration.ofSeconds(2)), result)
        assertEquals(1, requests.count { it == ChainCaptureFormat.GET_TRANSACTION to SELL_SIGNATURE })
    }

    @Test
    fun `a rate limited read is not retried even when the page has budget to spare`() {
        val result = LocalRpcServer(provider(rateLimited = SELL_SIGNATURE)).use { server ->
            lookup(server).recent(WalletActivityRequest(DECU, WalletActivityLimits.MAX_LIMIT, null), usage)
        }

        assertEquals(WalletActivityResult.Unavailable(WalletActivityUnavailable.PROVIDER_RATE_LIMITED, Duration.ofSeconds(2)), result)
        assertEquals(1, requests.count { it == ChainCaptureFormat.GET_TRANSACTION to SELL_SIGNATURE })
    }

    private fun lookup(server: LocalRpcServer): WalletActivityLookup = WalletActivityLookups.create(
        TestSettings(endpoint = server.endpoint, minRequestInterval = Duration.ofMillis(20)), json, testClock, transactionCache = null,
        ApiUsageRecorder.NONE, RpcLimiterRegistry())

    /** Answers from Decu's accumulator fixture, except a 429 with `Retry-After: 2` for every read of [rateLimited]. */
    private fun provider(rateLimited: String): (HttpExchange) -> Unit {
        val rpc = WalletActivityFixtures.decuAccumulator()
        return { exchange ->
            val request = json.readTree(exchange.requestBody.readAllBytes())
            val method = request["method"].asString()
            val params = json.convertValue(request["params"], List::class.java).map { requireNotNull(it) }
            val signature = (params.firstOrNull() as? String).takeIf { method == ChainCaptureFormat.GET_TRANSACTION }
            requests += method to signature
            if (signature == rateLimited) {
                LocalRpcServer.reply(exchange, 429, "", mapOf("Retry-After" to "2"))
            } else {
                val result = rpc.call(method, params, CaptureBudget(Int.MAX_VALUE, Long.MAX_VALUE))
                LocalRpcServer.reply(exchange, 200, """{"jsonrpc":"2.0","id":1,"result":$result}""")
            }
        }
    }

    private companion object {
        /** Decu's fixture listing has exactly five successful entries, so this page needs every request its budget allows. */
        const val FULL_PAGE_LIMIT = 5
    }
}
