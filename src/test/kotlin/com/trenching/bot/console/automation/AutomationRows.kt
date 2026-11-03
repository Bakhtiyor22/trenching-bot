package com.trenching.bot.console.automation

import com.trenching.bot.console.automation.AutomationFixtures.WALLET
import com.trenching.bot.console.automation.AutomationFixtures.mint
import com.trenching.bot.console.automation.AutomationFixtures.signature
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationSessionState
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSessionEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSweepRunEntity
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant

/** Rows written straight to the automation tables, for the repository and read API tests. */
object AutomationRows {
    fun session(id: String, state: AutomationSessionState, firstBuyAt: Instant, createdAt: Instant = firstBuyAt, firstItemId: String = "item-$id") =
        AutomationSessionEntity(id, WALLET, firstItemId, firstBuyAt, firstBuyAt, state, null, null, 0, createdAt)

    fun item(
        n: Int,
        sessionId: String,
        state: AutomationItemState = AutomationItemState.DISCOVERED,
        blockTime: Instant? = null,
        nextActionAt: Instant? = null,
        id: String = "item-$n",
        mint: Int = n,
        signature: Int = n,
    ) = AutomationItemEntity(
        id, sessionId, WALLET, mint(mint).value, signature(signature).value, 2, -1, 1000L + n, blockTime,
        BigInteger.valueOf(2_000_000_000), state, null, 0, null, null, null, null, null, null, nextActionAt, Instant.EPOCH, Instant.EPOCH,
    )

    /** A refused entry unless [runId] is given. */
    fun sweepRun(
        id: String,
        itemId: String,
        label: String,
        sessionId: String = "session-1",
        outcome: String = "MARKET_COVERAGE_ENDED",
        runId: String? = null,
        signal: Boolean = false,
        worstCasePnl: String = "0",
    ) = AutomationSweepRunEntity(
        id, itemId, sessionId, "decu-sweep-1", label, runId, runId?.let { "hash" }, runId?.let { "dip-paper-v6" }, outcome, null, signal,
        BigDecimal(worstCasePnl), BigDecimal(worstCasePnl), Instant.EPOCH,
    )
}
