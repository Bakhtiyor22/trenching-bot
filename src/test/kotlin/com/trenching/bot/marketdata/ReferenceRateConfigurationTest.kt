package com.trenching.bot.marketdata

import com.trenching.bot.apiusage.ApiUsageRecorder
import com.trenching.bot.marketdata.infrastructure.rates.BinanceSolUsdtRateSource
import com.trenching.bot.marketdata.infrastructure.rates.DisabledSolUsdtRateSource
import java.time.Clock
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class ReferenceRateConfigurationTest {
    private val configuration = ReferenceRateConfiguration()
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `rates are disabled unless enabled and then come from Binance`() {
        assertIs<DisabledSolUsdtRateSource>(configuration.solUsdtRateSource(ReferenceRateProperties(), mapper, Clock.systemUTC(), ApiUsageRecorder.NONE))
        assertIs<BinanceSolUsdtRateSource>(configuration.solUsdtRateSource(ReferenceRateProperties(enabled = true), mapper, Clock.systemUTC(), ApiUsageRecorder.NONE))
    }

    @Test
    fun `refuses a non-https base url and non-positive bounds`() {
        listOf(
            { ReferenceRateProperties(baseUrl = "http://api.binance.com") },
            { ReferenceRateProperties(maxRequestsPerLookup = 0) },
            { ReferenceRateProperties(maxCachedMinutes = 0) },
        ).forEach { assertFailsWith<IllegalArgumentException> { it() } }
    }
}
