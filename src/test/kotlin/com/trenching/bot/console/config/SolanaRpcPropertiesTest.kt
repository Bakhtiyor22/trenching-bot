package com.trenching.bot.console.config

import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class SolanaRpcPropertiesTest {
    private val primary = "https://primary.example.test/?api-key=PRIMARY-SECRET-1"
    private val backup = "https://backup.example.test/?api-key=BACKUP-SECRET-2"

    @Test
    fun `no backup behaves as before`() {
        assertEquals(null, SolanaRpcProperties(rpcUrl = primary).backupRpcUrl)
        SolanaRpcProperties(rpcUrl = primary, backupRpcUrl = "")
        SolanaRpcProperties()
    }

    @Test
    fun `a backup without a primary fails naming the property and never a url`() {
        listOf(null, "", "  ").forEach { missing ->
            val failure = assertFailsWith<IllegalArgumentException> { SolanaRpcProperties(rpcUrl = missing, backupRpcUrl = backup) }

            assertTrue(failure.message.orEmpty().contains("trenching.solana.backup-rpc-url"), failure.message)
            assertFalse(failure.message.orEmpty().contains("BACKUP-SECRET-2"))
        }
    }

    @Test
    fun `a backup equal to the primary fails naming the property and never a url`() {
        val failure = assertFailsWith<IllegalArgumentException> { SolanaRpcProperties(rpcUrl = primary, backupRpcUrl = " $primary ") }

        assertTrue(failure.message.orEmpty().contains("trenching.solana.backup-rpc-url"), failure.message)
        assertFalse(failure.message.orEmpty().contains("PRIMARY-SECRET-1"))
    }

    @Test
    fun `two different accounts are accepted and printing the properties shows no url`() {
        val properties = SolanaRpcProperties(rpcUrl = primary, backupRpcUrl = backup, usage = SolanaUsageProperties(primaryProjectId = "PROJECT-ID-ONE"))

        assertFalse(properties.toString().contains("SECRET"))
        assertFalse(properties.usage.toString().contains("PROJECT-ID-ONE"))
    }

    @Test
    fun `the usage defaults are the documented ones`() {
        val usage = SolanaUsageProperties()

        assertEquals("https://admin-api.helius.xyz", usage.baseUrl)
        assertEquals(Duration.ofMinutes(15), usage.refreshInterval)
        assertEquals(10, usage.lowBalancePercent)
        assertEquals(1_000L, usage.resumeCredits)
    }

    @Test
    fun `a usage base url is stored trimmed and must be https unless it is this machine`() {
        assertEquals("https://admin.example.test", SolanaUsageProperties(baseUrl = "  https://admin.example.test \n").baseUrl)
        listOf("http://localhost:8080", "http://127.0.0.1:1", "http://[::1]:9").forEach { SolanaUsageProperties(baseUrl = it) }
        listOf("http://admin.example.test", "http://localhost.example.test", "http://127.0.0.2").forEach { remote ->
            val failure = assertFailsWith<IllegalArgumentException> { SolanaUsageProperties(baseUrl = remote) }

            assertTrue(failure.message.orEmpty().contains("trenching.solana.usage.base-url"), failure.message)
            assertFalse(failure.message.orEmpty().contains("example"))
        }
    }

    @Test
    fun `each rpc url is checked with the transport's rule and a failure names its own property`() {
        listOf("http://rpc.example.test/k", "https://user:pw@rpc.example.test/k", "https://rpc.example.test/k#frag", "ftp://rpc.example.test", "not a url").forEach { bad ->
            val primaryFailure = assertFailsWith<IllegalArgumentException> { SolanaRpcProperties(rpcUrl = bad) }
            val backupFailure = assertFailsWith<IllegalArgumentException> { SolanaRpcProperties(rpcUrl = primary, backupRpcUrl = bad) }

            assertTrue(primaryFailure.message.orEmpty().startsWith("trenching.solana.rpc-url "), primaryFailure.message)
            assertTrue(backupFailure.message.orEmpty().startsWith("trenching.solana.backup-rpc-url "), backupFailure.message)
            assertFalse(primaryFailure.message.orEmpty().contains("example") || backupFailure.message.orEmpty().contains("example"))
        }
        SolanaRpcProperties(rpcUrl = " http://localhost:8899/ ", backupRpcUrl = "http://127.0.0.1:8900")
    }

    @Test
    fun `usage values that make no sense fail naming the property`() {
        listOf(
            "refresh-interval" to { SolanaUsageProperties(refreshInterval = Duration.ZERO) },
            "low-balance-percent" to { SolanaUsageProperties(lowBalancePercent = 101) },
            "low-balance-percent" to { SolanaUsageProperties(lowBalancePercent = -1) },
            "resume-credits" to { SolanaUsageProperties(resumeCredits = 0) },
            "base-url" to { SolanaUsageProperties(baseUrl = "ftp://example.test") },
            "base-url" to { SolanaUsageProperties(baseUrl = "not a url") },
        ).forEach { (name, create) ->
            val failure = assertFailsWith<IllegalArgumentException> { create() }

            assertTrue(failure.message.orEmpty().contains("trenching.solana.usage.$name"), failure.message)
        }
    }
}
