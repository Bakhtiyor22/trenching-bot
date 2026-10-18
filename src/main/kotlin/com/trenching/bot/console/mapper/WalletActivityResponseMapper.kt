package com.trenching.bot.console.mapper

import com.trenching.bot.console.api.dto.WalletActionCaptureResponse
import com.trenching.bot.console.api.dto.WalletActionResponse
import com.trenching.bot.console.api.dto.WalletActivityResponse
import com.trenching.bot.console.domain.CaptureReference
import com.trenching.bot.console.domain.WalletActionView
import com.trenching.bot.console.domain.WalletActivityView
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.ReportingPolicy

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, uses = [ConsoleValueMapper::class],
    unmappedTargetPolicy = ReportingPolicy.ERROR)
interface WalletActivityResponseMapper {
    @Mapping(target = ".", source = "page")
    @Mapping(target = "actions", source = "actions")
    fun toResponse(view: WalletActivityView): WalletActivityResponse

    @Mapping(target = ".", source = "action")
    fun toResponse(action: WalletActionView): WalletActionResponse

    fun toResponse(reference: CaptureReference): WalletActionCaptureResponse
}
