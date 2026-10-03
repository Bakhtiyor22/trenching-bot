package com.trenching.bot.console.api

import com.trenching.bot.console.api.dto.SolanaAddressCheckRequest
import com.trenching.bot.console.api.dto.SolanaAddressCheckResponse
import com.trenching.bot.console.api.dto.SolanaCheckResponse
import com.trenching.bot.console.api.dto.SolanaStatusResponse
import com.trenching.bot.console.application.SolanaProbeService
import com.trenching.bot.console.mapper.SolanaProbeMapper
import com.trenching.bot.console.config.ConditionalOnConsoleEnabled
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/solana")
@ConditionalOnConsoleEnabled
class SolanaController(
    private val solanaProbeService: SolanaProbeService,
    private val solanaProbeMapper: SolanaProbeMapper,
) {
    @GetMapping("/status")
    fun status(): SolanaStatusResponse = solanaProbeMapper.toResponse(solanaProbeService.status())

    @PostMapping("/check")
    fun check(): SolanaCheckResponse = solanaProbeMapper.toResponse(solanaProbeService.check())

    @PostMapping("/address-check")
    fun addressCheck(@RequestBody request: SolanaAddressCheckRequest): SolanaAddressCheckResponse =
        solanaProbeMapper.toResponse(solanaProbeService.checkAddress(request.address))
}
