package com.trenching.bot.console.api

import com.trenching.bot.console.api.dto.SolUsdtPriceResponse
import com.trenching.bot.console.application.RunUsdtViewService
import com.trenching.bot.console.config.ConditionalOnConsoleEnabled
import com.trenching.bot.console.mapper.RunUsdtViewMapper
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/market")
@ConditionalOnConsoleEnabled
class MarketController(
    private val runUsdtViewService: RunUsdtViewService,
    private val runUsdtViewMapper: RunUsdtViewMapper,
) {
    @GetMapping("/sol-usdt")
    fun solUsdt(): SolUsdtPriceResponse = runUsdtViewMapper.toPrice(runUsdtViewService.currentSolPrice())
}
