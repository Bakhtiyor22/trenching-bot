package com.trenching.bot.console.mapper

import com.trenching.bot.console.solana.accounts.RpcAccountTestData
import com.trenching.bot.console.solana.accounts.application.RpcAccountStanding
import com.trenching.bot.console.solana.accounts.application.RpcAccountStatus
import com.trenching.bot.console.solana.accounts.application.RpcAccountsSnapshot
import com.trenching.bot.console.solana.accounts.domain.BalanceProblem
import com.trenching.bot.console.solana.accounts.domain.RpcAccountName
import com.trenching.bot.console.solana.accounts.domain.RpcAccountState
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class RpcAccountsMapperTest {
    private val mapper = RpcAccountsMapperImpl()
    private val now = RpcAccountTestData.NOW

    @Test
    fun `an account's balance, standing and problem reach the response, and a wrong network keeps its own state`() {
        val read = RpcAccountState.fresh(RpcAccountName.PRIMARY, now).copy(balance = RpcAccountTestData.balance(640_000, plan = "Developer"), balanceCheckedAt = now)
        val unread = RpcAccountState.fresh(RpcAccountName.BACKUP, now).copy(balanceProblem = BalanceProblem.NO_PROJECT_ID, balanceCheckedAt = now)
        val snapshot = RpcAccountsSnapshot(
            RpcAccountName.PRIMARY,
            listOf(
                RpcAccountStatus(RpcAccountName.PRIMARY, RpcAccountStanding.ACTIVE, "primary.example.test", read),
                RpcAccountStatus(RpcAccountName.BACKUP, RpcAccountStanding.WRONG_NETWORK, "backup.example.test", unread),
            ),
        )

        val response = mapper.toResponse(snapshot)

        assertEquals("primary", response.active)
        val primary = response.accounts[0]
        assertEquals(listOf("primary", "ACTIVE", true), listOf(primary.name, primary.state, primary.configured))
        assertEquals(listOf(640_000L, 360_000L, 1_000_000L), listOf(primary.creditsRemaining, primary.creditsUsed, primary.creditsLimit))
        assertEquals(LocalDate.parse("2026-12-05"), primary.cycleEndsOn)
        assertEquals("Developer", primary.plan)
        assertNull(primary.balanceProblem)
        val backup = response.accounts[1]
        assertEquals("WRONG_NETWORK", backup.state)
        assertEquals("NO_PROJECT_ID", backup.balanceProblem)
        assertNull(backup.creditsRemaining)
    }
}
