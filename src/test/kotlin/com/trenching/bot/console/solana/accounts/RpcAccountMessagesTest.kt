package com.trenching.bot.console.solana.accounts

import com.trenching.bot.console.solana.accounts.RpcAccountTestData.NOW
import com.trenching.bot.console.solana.accounts.RpcAccountTestData.balance
import com.trenching.bot.console.solana.accounts.domain.RpcAccountMessages
import com.trenching.bot.console.solana.accounts.domain.RpcAccountName
import com.trenching.bot.console.solana.accounts.domain.RpcAccountState
import com.trenching.bot.notification.NotificationKind
import com.trenching.bot.notification.NotificationLink
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class RpcAccountMessagesTest {
    private companion object {
        val UTC: ZoneId = ZoneOffset.UTC
        val TASHKENT: ZoneId = ZoneOffset.ofHours(5)
    }

    private val primary = RpcAccountState.fresh(RpcAccountName.PRIMARY, NOW)
    private val backup = RpcAccountState.fresh(RpcAccountName.BACKUP, NOW).copy(balance = balance(450_000), balanceCheckedAt = NOW)

    @Test
    fun `a switch to the backup names both accounts, the credits left and the cycle end, and links the Solana page`() {
        val message = RpcAccountMessages.switched(RpcAccountName.PRIMARY, RpcAccountName.BACKUP, NOW, listOf(primary.copy(outOfCreditsSince = NOW), backup), UTC)

        assertEquals("RPC_ACCOUNT_SWITCHED|primary|backup|2026-11-22T10:00:00Z", message.key)
        assertEquals(NotificationKind.RPC_ACCOUNT_SWITCHED, message.kind)
        assertEquals("RPC switched to the backup account", message.title)
        assertEquals(
            listOf(
                "Capture now uses the backup account.",
                "primary: out of credits since 22 Nov 10:00",
                "backup: 450,000 of 1,000,000 credits left, the cycle ends 2026-12-05 (UTC)",
            ),
            message.lines,
        )
        assertEquals(listOf(NotificationLink("Solana page", "/solana")), message.links)
    }

    @Test
    fun `times read in the zone the caller passes, not in UTC`() {
        val message = RpcAccountMessages.switched(RpcAccountName.PRIMARY, RpcAccountName.BACKUP, NOW, listOf(primary.copy(outOfCreditsSince = NOW), backup), TASHKENT)

        assertEquals("primary: out of credits since 22 Nov 15:00", message.lines[1])
    }

    @Test
    fun `a return to the primary and the loss of every account have their own titles and keys`() {
        val back = RpcAccountMessages.switched(RpcAccountName.BACKUP, RpcAccountName.PRIMARY, NOW, listOf(primary, backup), UTC)
        val none = RpcAccountMessages.switched(RpcAccountName.BACKUP, null, Instant.parse("2026-11-23T00:00:00Z"), listOf(primary.copy(outOfCreditsSince = NOW), backup.copy(outOfCreditsSince = NOW)), UTC)

        assertEquals("RPC is back on the primary account", back.title)
        assertEquals("RPC_ACCOUNT_SWITCHED|backup|primary|2026-11-22T10:00:00Z", back.key)
        assertEquals("No RPC account has credits", none.title)
        assertEquals("RPC_ACCOUNT_SWITCHED|backup|none|2026-11-23T00:00:00Z", none.key)
        assertEquals("Capture is held until an account has credits again.", none.lines.first())
        assertEquals("primary: credits not known", back.lines[1])
    }

    @Test
    fun `a low balance is keyed by account and cycle end and says how much is left of the limit`() {
        val message = RpcAccountMessages.lowBalance(backup.copy(balance = balance(90_000)))

        assertEquals("RPC_BALANCE_LOW|backup|2026-12-05", message.key)
        assertEquals("RPC credits are low on the backup account", message.title)
        assertEquals(listOf("backup: 90,000 of 1,000,000 credits left, the cycle ends 2026-12-05 (UTC)"), message.lines)
        assertEquals("/solana", message.links.single().path)
        assertNull(message.lines.firstOrNull { it.contains("api-key") })
        assertFalse(message.toString().contains("http"))
    }
}
