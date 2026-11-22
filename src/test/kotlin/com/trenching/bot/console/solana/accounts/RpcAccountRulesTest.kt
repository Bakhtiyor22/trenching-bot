package com.trenching.bot.console.solana.accounts

import com.trenching.bot.console.solana.accounts.RpcAccountTestData.NOW
import com.trenching.bot.console.solana.accounts.RpcAccountTestData.balance
import com.trenching.bot.console.solana.accounts.domain.AccountNetwork
import com.trenching.bot.console.solana.accounts.domain.BalanceProblem
import com.trenching.bot.console.solana.accounts.domain.BalanceRead
import com.trenching.bot.console.solana.accounts.domain.RpcAccountName
import com.trenching.bot.console.solana.accounts.domain.RpcAccountRules
import com.trenching.bot.console.solana.accounts.domain.RpcAccountState
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class RpcAccountRulesTest {
    private val fresh = RpcAccountState.fresh(RpcAccountName.PRIMARY, NOW)
    private val later = NOW.plusSeconds(900)

    @Test
    fun `the first configured account with credits is active and the primary wins when both have`() {
        val configs = RpcAccountTestData.both()
        val states = mapOf(RpcAccountName.PRIMARY to fresh, RpcAccountName.BACKUP to RpcAccountState.fresh(RpcAccountName.BACKUP, NOW))

        val mainnet = RpcAccountName.entries.associateWith { AccountNetwork.MAINNET }

        assertEquals(RpcAccountName.PRIMARY, RpcAccountRules.active(configs, states, mainnet))
        assertEquals(RpcAccountName.BACKUP, RpcAccountRules.active(configs, states + (RpcAccountName.PRIMARY to fresh.copy(outOfCreditsSince = NOW)), mainnet))
        assertNull(RpcAccountRules.active(configs, states.mapValues { it.value.copy(outOfCreditsSince = NOW) }, mainnet))
    }

    @Test
    fun `a backup that is not verified as mainnet is never active, though it has credits`() {
        val configs = RpcAccountTestData.both()
        val states = mapOf(RpcAccountName.PRIMARY to fresh.copy(outOfCreditsSince = NOW), RpcAccountName.BACKUP to RpcAccountState.fresh(RpcAccountName.BACKUP, NOW))
        val networks = mapOf(RpcAccountName.PRIMARY to AccountNetwork.MAINNET, RpcAccountName.BACKUP to AccountNetwork.UNVERIFIED)

        assertNull(RpcAccountRules.active(configs, states, networks))
        assertNull(RpcAccountRules.active(configs, states, networks + (RpcAccountName.BACKUP to AccountNetwork.WRONG_NETWORK)))
        assertEquals(RpcAccountName.BACKUP, RpcAccountRules.withCredits(configs, states))
        assertEquals(RpcAccountName.BACKUP, RpcAccountRules.active(configs, states, networks + (RpcAccountName.BACKUP to AccountNetwork.MAINNET)))
    }

    @Test
    fun `a read of zero credits marks the account out and keeps the time it first ran out`() {
        val out = RpcAccountRules.afterRead(fresh, BalanceRead.Read(balance(0)), NOW, resumeCredits = 1_000)
        val again = RpcAccountRules.afterRead(out, BalanceRead.Read(balance(0)), later, resumeCredits = 1_000)

        assertEquals(NOW, out.outOfCreditsSince)
        assertEquals(NOW, again.outOfCreditsSince)
        assertEquals(later, again.balanceCheckedAt)
    }

    @ParameterizedTest
    @CsvSource("999, false", "1000, true", "1001, true", "500000, true")
    fun `an account that is out returns exactly when the read reaches the resume credits`(remaining: Long, returns: Boolean) {
        val out = fresh.copy(outOfCreditsSince = NOW)

        val after = RpcAccountRules.afterRead(out, BalanceRead.Read(balance(remaining)), later, resumeCredits = 1_000)

        assertEquals(returns, after.hasCredits)
    }

    @Test
    fun `a read with credits changes nothing about an account that is not out`() {
        val after = RpcAccountRules.afterRead(fresh, BalanceRead.Read(balance(5)), later, resumeCredits = 1_000)

        assertTrue(after.hasCredits)
        assertEquals(5, after.balance?.creditsRemaining)
    }

    @Test
    fun `a failed read keeps the balance and the out-of-credits mark and records the problem`() {
        val before = RpcAccountRules.afterRead(fresh.copy(outOfCreditsSince = NOW), BalanceRead.Read(balance(400)), NOW, resumeCredits = 1_000)

        val after = RpcAccountRules.afterRead(before, BalanceRead.Unavailable(BalanceProblem.RATE_LIMITED), later, resumeCredits = 1_000)

        assertEquals(before.balance, after.balance)
        assertEquals(NOW, after.outOfCreditsSince)
        assertEquals(BalanceProblem.RATE_LIMITED, after.balanceProblem)
        assertEquals(later, after.balanceCheckedAt)
    }

    @Test
    fun `a good read clears the problem`() {
        val failed = RpcAccountRules.afterRead(fresh, BalanceRead.Unavailable(BalanceProblem.MALFORMED), NOW, resumeCredits = 1_000)

        assertNull(RpcAccountRules.afterRead(failed, BalanceRead.Read(balance(10_000)), later, resumeCredits = 1_000).balanceProblem)
    }

    @ParameterizedTest
    @CsvSource(
        "99999, 1000000, 10, true",
        "100000, 1000000, 10, false",
        "0, 1000000, 10, true",
        "5, 0, 10, false",
        "1, 100, 0, false",
        "9223372036854775807, 9223372036854775807, 100, false",
    )
    fun `low means remaining times 100 below limit times the percent, in integers`(remaining: Long, limit: Long, percent: Int, low: Boolean) {
        assertEquals(low, RpcAccountRules.isLow(balance(remaining, limit = limit, used = 0), percent))
    }

    @Test
    fun `an account out of credits that answers is back, and one that is not out is left alone`() {
        assertTrue(RpcAccountRules.afterAnswer(fresh.copy(outOfCreditsSince = NOW), later).hasCredits)
        assertEquals(fresh, RpcAccountRules.afterAnswer(fresh, later))
        assertFalse(RpcAccountRules.afterExhausted(fresh, later).hasCredits)
        assertEquals(NOW, RpcAccountRules.afterExhausted(fresh.copy(outOfCreditsSince = NOW), later).outOfCreditsSince)
    }
}
