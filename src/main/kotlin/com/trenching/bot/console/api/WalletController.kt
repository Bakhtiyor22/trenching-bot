package com.trenching.bot.console.api

import com.trenching.bot.console.api.dto.WalletActivityResponse
import com.trenching.bot.console.capture.WalletActivityConsoleService
import com.trenching.bot.console.config.ConditionalOnConsoleEnabled
import com.trenching.bot.console.mapper.WalletActivityResponseMapper
import com.trenching.bot.walletactivity.WalletActivityLimits
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Live provider reads: the console calls it only on an explicit user action, never on a poll. */
@RestController
@RequestMapping("/api/wallets")
@Profile("local & !prod")
@ConditionalOnConsoleEnabled
class WalletController(private val activity: WalletActivityConsoleService, private val mapper: WalletActivityResponseMapper) {
    @GetMapping("/{address}/activity")
    fun activity(
        @PathVariable address: String,
        @RequestParam(defaultValue = "${WalletActivityLimits.DEFAULT_LIMIT}") limit: Int,
        @RequestParam(required = false) before: String?,
        @RequestParam(defaultValue = "false") refresh: Boolean,
    ): WalletActivityResponse = mapper.toResponse(activity.recent(address, limit, before, refresh))
}
