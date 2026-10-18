package com.trenching.bot.console.api

import com.trenching.bot.console.api.dto.WalletActivityResponse
import com.trenching.bot.console.application.WalletActivityConsoleService
import com.trenching.bot.console.config.ConditionalOnConsoleEnabled
import com.trenching.bot.console.mapper.WalletActivityResponseMapper
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/wallets")
@ConditionalOnConsoleEnabled
class WalletController(
    private val walletActivityConsoleService: WalletActivityConsoleService,
    private val walletActivityResponseMapper: WalletActivityResponseMapper,
) {
    @GetMapping("/{address}/activity")
    fun activity(
        @PathVariable address: String,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) before: String?,
        @RequestParam(defaultValue = "false") refresh: Boolean,
    ): WalletActivityResponse = walletActivityResponseMapper.toResponse(walletActivityConsoleService.activity(address, limit, before, refresh))
}
