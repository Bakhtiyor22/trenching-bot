package com.trenching.bot.console.solana

import com.trenching.bot.chaincapture.RpcEndpointChoice
import com.trenching.bot.chaincapture.RpcEndpointTarget
import com.trenching.bot.console.solana.accounts.ScriptedRpcEndpoints
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** The probe checks whichever account is active at the moment of the call. */
class JsonRpcSolanaProbeAccountsTest {
    private val rest = SolanaRestClients.create(Duration.ofSeconds(2), Duration.ofSeconds(2))

    private fun probe(endpoints: ScriptedRpcEndpoints) =
        JsonRpcSolanaProbe(rest, SolanaRpcTestData.mapper, endpoints, primaryHost = "primary.example.test")

    @Test
    fun `a check goes to the account the source names at that moment and follows a failover`() {
        FakeSolanaRpcServer.start().use { primary ->
            FakeSolanaRpcServer.start().use { backup ->
                primary.respondWith(200, SolanaRpcTestData.result("\"ok\""))
                backup.respondWith(200, SolanaRpcTestData.result("\"ok\""))
                val endpoints = ScriptedRpcEndpoints(RpcEndpointChoice.Use(RpcEndpointTarget("primary", primary.url())))
                val probe = probe(endpoints)

                probe.check()
                val primaryRequests = primary.requestCount
                assertTrue(primaryRequests > 0)
                assertEquals(0, backup.requestCount)
                endpoints.choice = RpcEndpointChoice.Use(RpcEndpointTarget("backup", backup.url()))
                probe.check()

                assertEquals(primaryRequests, primary.requestCount, "after the switch the primary is not asked again")
                assertEquals(primaryRequests, backup.requestCount, "the same check now goes to the backup")
                assertEquals("127.0.0.1", probe.host)
            }
        }
    }

    @Test
    fun `with every account out of credits a check sends nothing, is unavailable and the host stays the primary's`() {
        val probe = probe(ScriptedRpcEndpoints(RpcEndpointChoice.OutOfCredits))

        val outcome = assertIs<ProbeOutcome.Unavailable>(probe.check())

        assertEquals("no RPC account has credits", outcome.reason)
        assertEquals("primary.example.test", probe.host)
    }

    @Test
    fun `with no account configured a check is not configured and has no host`() {
        val probe = probe(ScriptedRpcEndpoints(RpcEndpointChoice.NotConfigured))

        assertEquals(ProbeOutcome.NotConfigured, probe.check())
        assertNull(probe.host)
    }
}
