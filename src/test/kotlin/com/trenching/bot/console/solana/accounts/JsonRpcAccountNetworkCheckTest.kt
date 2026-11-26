package com.trenching.bot.console.solana.accounts

import com.trenching.bot.apiusage.ApiCall
import com.trenching.bot.apiusage.ApiCallOutcome
import com.trenching.bot.apiusage.ApiProvider
import com.trenching.bot.apiusage.ApiPurpose
import com.trenching.bot.console.solana.FakeSolanaRpcServer
import com.trenching.bot.console.solana.SolanaCluster
import com.trenching.bot.console.solana.SolanaRestClients
import com.trenching.bot.console.solana.SolanaRpcTestData
import com.trenching.bot.console.solana.accounts.domain.NetworkCheck
import com.trenching.bot.console.solana.accounts.domain.RpcAccountConfig
import com.trenching.bot.console.solana.accounts.domain.RpcAccountConfigs
import com.trenching.bot.console.solana.accounts.domain.RpcAccountName
import com.trenching.bot.console.solana.accounts.infrastructure.rpc.JsonRpcAccountNetworkCheck
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class JsonRpcAccountNetworkCheckTest {
    private val calls = ConcurrentLinkedQueue<ApiCall>()
    private val mainnet = "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d"

    private fun check(server: FakeSolanaRpcServer): NetworkCheck {
        val configs = RpcAccountConfigs(listOf(RpcAccountTestData.primary(), RpcAccountConfig(RpcAccountName.BACKUP, server.url(), "127.0.0.1", null)))
        val rest = SolanaRestClients.create(Duration.ofSeconds(2), Duration.ofMillis(500))
        return JsonRpcAccountNetworkCheck(configs, rest, SolanaRpcTestData.mapper) { calls += it }.check(RpcAccountName.BACKUP)
    }

    @Test
    fun `the mainnet genesis hash is mainnet and the request is recorded as a mainnet check`() {
        FakeSolanaRpcServer.start().use { server ->
            server.respondWith(200, SolanaRpcTestData.result("\"$mainnet\""))

            assertEquals(NetworkCheck.Mainnet, check(server))

            assertEquals(1, server.requestCount)
            val call = calls.single()
            assertEquals(ApiProvider.SOLANA_RPC, call.provider)
            assertEquals(ApiPurpose.MAINNET_CHECK, ApiPurpose.of(call.provider, call.operation))
            assertEquals(ApiCallOutcome.OK, call.outcome)
        }
    }

    @Test
    fun `another network's genesis hash is another network`() {
        FakeSolanaRpcServer.start().use { server ->
            server.respondWith(200, SolanaRpcTestData.result("\"EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG\""))

            assertEquals(NetworkCheck.OtherNetwork, check(server))
            assertEquals(SolanaCluster.DEVNET, SolanaCluster.fromGenesisHash("EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG"))
        }
    }

    @Test
    fun `an unknown hash is not mainnet`() {
        FakeSolanaRpcServer.start().use { server ->
            server.respondWith(200, SolanaRpcTestData.result("\"unknown-genesis\""))

            assertEquals(NetworkCheck.OtherNetwork, check(server))
        }
    }

    @Test
    fun `a provider failure, a timeout and an answer that is no hash say nothing about the network`() {
        FakeSolanaRpcServer.start().use { server ->
            server.respondWith(503)
            assertEquals(NetworkCheck.Unknown, check(server))
            server.respondWith(200, SolanaRpcTestData.result("12"))
            assertEquals(NetworkCheck.Unknown, check(server))
            server.respondWith(200, "not json")
            assertEquals(NetworkCheck.Unknown, check(server))
        }
        FakeSolanaRpcServer.start().use { slow ->
            slow.hangUntilClosed()
            assertEquals(NetworkCheck.Unknown, check(slow))
        }
    }
}
