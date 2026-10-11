package com.trenching.bot.walletactivity

import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.walletactivity.domain.TargetBalanceChange
import com.trenching.bot.walletactivity.domain.TokenAccountFirstBuyRule
import com.trenching.bot.walletactivity.domain.TokenAccountRecord
import com.trenching.bot.walletactivity.domain.TokenAccountRecordKind
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class TokenAccountFirstBuyRuleTest {
    @Test
    fun `the earliest decoded buy that credits the token account with nothing blocking it is found`() {
        val result = evaluate(listOf(
            record(B, 100, buy()),
            record(LATER_BUY, 120, buy(instructionIndex = 4)),
            record(EARLIER, 90, TokenAccountRecordKind.Irrelevant),
        ))
        assertEquals(TokenAccountFirstBuy.Found(signature(B), Slot.of(100), 2), result)
    }

    @Test
    fun `buys are ordered by slot regardless of record order`() {
        val result = evaluate(listOf(record(LATER_BUY, 120, buy(instructionIndex = 4)), record(B, 100, buy())))
        assertEquals(TokenAccountFirstBuy.Found(signature(B), Slot.of(100), 2), result)
    }

    @Test
    fun `another decoded buy in the earliest buy's slot is not ordered by signature and leaves it not established`() {
        val result = evaluate(listOf(
            record(LATER_BUY, 100, buy(instructionIndex = 4)),
            record(B, 100, buy()),
            record(UNKNOWN, 100, buy(credits = false)),
            record(EARLIER, 120, buy()),
        ))
        assertEquals(TokenAccountFirstBuy.NotEstablished(TokenAccountGap.SAME_SLOT_BUY, listOf(signature(LATER_BUY), signature(UNKNOWN))), result)
    }

    @Test
    fun `a history that stopped at the record limit or scan bound is not exhausted`() {
        listOf(WalletHistoryDiscoveryStop.LIMIT_REACHED, WalletHistoryDiscoveryStop.SCAN_BOUND, null).forEach { stop ->
            assertEquals(TokenAccountFirstBuy.NotEstablished(TokenAccountGap.HISTORY_NOT_EXHAUSTED, emptyList()),
                TokenAccountFirstBuyRule.evaluate(stop, listOf(record(B, 100, buy()))), "$stop")
        }
    }

    @Test
    fun `an exhausted history without a decoded buy of the mint has no buy`() {
        val result = evaluate(listOf(
            record(EARLIER, 90, TokenAccountRecordKind.Undecoded(TargetBalanceChange.INCREASED)),
            record(UNKNOWN, 80, TokenAccountRecordKind.Unknown),
        ))
        assertEquals(TokenAccountFirstBuy.NoBuy, result)
    }

    @Test
    fun `an earliest buy that does not credit the token account is not established`() {
        val result = evaluate(listOf(record(B, 100, buy(credits = false)), record(LATER_BUY, 120, buy())))
        assertEquals(TokenAccountFirstBuy.NotEstablished(TokenAccountGap.NON_ATA_CREDIT, listOf(signature(B))), result)
    }

    @Test
    fun `an earlier wallet signed undecoded record that raised or may have raised the balance blocks`() {
        listOf(TargetBalanceChange.INCREASED, TargetBalanceChange.UNMEASURABLE).forEach { change ->
            val result = evaluate(listOf(record(B, 100, buy()), record(EARLIER, 90, TokenAccountRecordKind.Undecoded(change))))
            assertEquals(TokenAccountFirstBuy.NotEstablished(TokenAccountGap.EARLIER_UNDECODED_ACQUISITION, listOf(signature(EARLIER))),
                result, "$change")
        }
    }

    @Test
    fun `an earlier undecoded record that did not raise the balance does not block`() {
        val result = evaluate(listOf(record(B, 100, buy()), record(EARLIER, 90, TokenAccountRecordKind.Undecoded(TargetBalanceChange.NOT_INCREASED))))
        assertEquals(TokenAccountFirstBuy.Found(signature(B), Slot.of(100), 2), result)
    }

    @Test
    fun `an earlier record without an artifact blocks as unknown`() {
        val result = evaluate(listOf(record(B, 100, buy()), record(UNKNOWN, 90, TokenAccountRecordKind.Unknown)))
        assertEquals(TokenAccountFirstBuy.NotEstablished(TokenAccountGap.EARLIER_UNKNOWN_RECORD, listOf(signature(UNKNOWN))), result)
    }

    @Test
    fun `a same slot record blocks whichever side of the buy its signature sorts on`() {
        val sortsAfter = evaluate(listOf(record(B, 100, buy()), record(LATER_BUY, 100, TokenAccountRecordKind.Unknown)))
        assertEquals(TokenAccountFirstBuy.NotEstablished(TokenAccountGap.EARLIER_UNKNOWN_RECORD, listOf(signature(LATER_BUY))), sortsAfter)
        val sortsBefore = evaluate(listOf(record(B, 100, buy()),
            record(EARLIER, 100, TokenAccountRecordKind.Undecoded(TargetBalanceChange.UNMEASURABLE))))
        assertEquals(TokenAccountFirstBuy.NotEstablished(TokenAccountGap.EARLIER_UNDECODED_ACQUISITION, listOf(signature(EARLIER))), sortsBefore)
    }

    @Test
    fun `later records never block`() {
        val result = evaluate(listOf(
            record(B, 100, buy()),
            record(UNKNOWN, 101, TokenAccountRecordKind.Unknown),
            record(EARLIER, 102, TokenAccountRecordKind.Undecoded(TargetBalanceChange.INCREASED)),
        ))
        assertEquals(TokenAccountFirstBuy.Found(signature(B), Slot.of(100), 2), result)
    }

    @Test
    fun `non signer wallet absent and failed records are ignored`() {
        val result = evaluate(listOf(record(B, 100, buy()), record(EARLIER, 90, TokenAccountRecordKind.Irrelevant),
            record(UNKNOWN, 100, TokenAccountRecordKind.Irrelevant)))
        assertEquals(TokenAccountFirstBuy.Found(signature(B), Slot.of(100), 2), result)
    }

    @Test
    fun `an undecoded acquisition outranks an unknown record and lists only its own signatures`() {
        val result = evaluate(listOf(
            record(B, 100, buy()),
            record(UNKNOWN, 80, TokenAccountRecordKind.Unknown),
            record(LATER_BUY, 95, TokenAccountRecordKind.Undecoded(TargetBalanceChange.INCREASED)),
            record(EARLIER, 90, TokenAccountRecordKind.Undecoded(TargetBalanceChange.UNMEASURABLE)),
        ))
        assertEquals(TokenAccountFirstBuy.NotEstablished(TokenAccountGap.EARLIER_UNDECODED_ACQUISITION,
            listOf(signature(EARLIER), signature(LATER_BUY))), result)
    }

    @Test
    fun `a buy that does not credit the token account is reported before earlier blockers`() {
        val result = evaluate(listOf(record(B, 100, buy(credits = false)), record(UNKNOWN, 90, TokenAccountRecordKind.Unknown)))
        assertEquals(TokenAccountFirstBuy.NotEstablished(TokenAccountGap.NON_ATA_CREDIT, listOf(signature(B))), result)
    }

    private fun evaluate(records: List<TokenAccountRecord>): TokenAccountFirstBuy =
        TokenAccountFirstBuyRule.evaluate(WalletHistoryDiscoveryStop.HISTORY_END, records)

    private fun buy(instructionIndex: Int = 2, credits: Boolean = true) = TokenAccountRecordKind.TargetBuy(instructionIndex, credits)

    private fun record(value: String, slot: Long, kind: TokenAccountRecordKind) = TokenAccountRecord(signature(value), Slot.of(slot), kind)

    private fun signature(value: String) = TransactionSignature.of(value)

    private companion object {
        val EARLIER = "1".repeat(63) + "2"
        val B = "1".repeat(63) + "3"
        val LATER_BUY = "1".repeat(63) + "4"
        val UNKNOWN = "1".repeat(63) + "5"
    }
}
