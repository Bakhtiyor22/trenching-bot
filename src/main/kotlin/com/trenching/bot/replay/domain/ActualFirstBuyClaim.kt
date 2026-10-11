package com.trenching.bot.replay.domain

import com.trenching.bot.protocol.pumpfun.PumpFunCoverage
import com.trenching.bot.walletactivity.ActualFirstBuy
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The exact claim a proven actual first buy supports, per the wallet-token capture plan; "proven" is never shown without it. */
object ActualFirstBuyClaim {
    private val COVERAGE_DATE: String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC).format(PumpFunCoverage.ACCUMULATOR_REQUIRED_FROM)

    fun of(proven: ActualFirstBuy.Proven): String {
        val creationSlot = proven.creationSlot.value
        return "Actual first Pump.fun curve buy proven for documented routes, for a mint created after $COVERAGE_DATE " +
            "(creation ${proven.creationSignature.value} at slot $creationSlot; " +
            "scanned Pump and PumpSwap accumulators to below $creationSlot)"
    }
}
