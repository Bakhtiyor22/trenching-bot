package com.trenching.bot.replay

import com.trenching.bot.replay.api.CommandArguments
import com.trenching.bot.replay.api.ReplayMarketArgumentParser
import com.trenching.bot.replay.api.WalletHistoryArgumentParser
import com.trenching.bot.replay.domain.ReplayMarketCaptureRequest
import com.trenching.bot.walletactivity.WalletHistoryCaptureRequest
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Test

class CaptureCommandArgumentsTest {
    private val market = arrayOf("--wallet-capture-directory", "wallet", "--directory", "markets")
    private val wallet = arrayOf("--wallet", "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9", "--directory", "wallet")

    private fun market(vararg extra: String) =
        assertIs<CommandArguments.Valid<ReplayMarketCaptureRequest>>(ReplayMarketArgumentParser.capture(market + extra)).value

    private fun wallet(vararg extra: String) =
        assertIs<CommandArguments.Valid<WalletHistoryCaptureRequest>>(WalletHistoryArgumentParser.capture(wallet + extra)).value

    @Test
    fun `the operation deadline defaults to ten minutes`() {
        assertEquals(Duration.ofMinutes(10), market().operationTimeout)
        assertEquals(Duration.ofMinutes(10), wallet().operationTimeout)
    }

    @Test
    fun `an operation deadline from one to thirty minutes is accepted`() {
        assertEquals(Duration.ofMinutes(30), market("--operation-timeout-minutes", "30").operationTimeout)
        assertEquals(Duration.ofMinutes(1), wallet("--operation-timeout-minutes", "1").operationTimeout)
    }

    @Test
    fun `an operation deadline outside one to thirty minutes is invalid`() {
        listOf("0", "31", "1.5", "ten").forEach { minutes ->
            assertEquals(CommandArguments.Invalid, ReplayMarketArgumentParser.capture(market + arrayOf("--operation-timeout-minutes", minutes)), minutes)
            assertEquals(CommandArguments.Invalid, WalletHistoryArgumentParser.capture(wallet + arrayOf("--operation-timeout-minutes", minutes)), minutes)
        }
    }
}
