package com.trenching.bot.console.application

import com.trenching.bot.console.domain.RunUsdtView
import com.trenching.bot.marketdata.RateLookup
import com.trenching.bot.marketdata.SolUsdtRateSource
import org.springframework.stereotype.Service

/**
 * USDT display views of stored runs. The run is read first through [ConsoleService]; rates are fetched afterwards, outside
 * any database transaction, and nothing is written.
 */
@Service
class RunUsdtViewService(
    private val consoleService: ConsoleService,
    private val rates: SolUsdtRateSource,
) {
    fun view(runId: String): RunUsdtView {
        val detail = consoleService.detail(runId)
        return UsdtConversion.convert(detail, consoleService.observations(runId), rates::minuteRates)
    }

    fun currentSolPrice(): RateLookup = rates.current()
}
