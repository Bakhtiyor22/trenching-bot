package com.trenching.bot.console.mapper

import com.trenching.bot.console.api.dto.WalletActionResponse
import com.trenching.bot.console.api.dto.WalletActivityResponse
import com.trenching.bot.console.capture.WalletActionView
import com.trenching.bot.console.capture.WalletActivityView
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.Mappings
import org.mapstruct.ReportingPolicy

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, uses = [ConsoleValueMapper::class], unmappedTargetPolicy = ReportingPolicy.ERROR)
interface WalletActivityResponseMapper {
    @Mappings(
        Mapping(target = "wallet", source = "page.wallet"),
        Mapping(target = "source", source = "page.source"),
        Mapping(target = "unreadable", source = "page.unreadable"),
        Mapping(target = "scanned", source = "page.scanned"),
        Mapping(target = "nextBefore", source = "page.nextBefore"),
        Mapping(target = "requestsUsed", source = "page.requestsUsed"),
    )
    fun toResponse(view: WalletActivityView): WalletActivityResponse

    @Mappings(
        Mapping(target = "signature", source = "action.signature"),
        Mapping(target = "instructionIndex", source = "action.instructionIndex"),
        Mapping(target = "innerIndex", source = "action.innerIndex"),
        Mapping(target = "slot", source = "action.slot"),
        Mapping(target = "blockTime", source = "action.blockTime"),
        Mapping(target = "side", source = "action.side"),
        Mapping(target = "mint", source = "action.mint"),
        Mapping(target = "solLamports", source = "action.solLamports"),
        Mapping(target = "tokenRaw", source = "action.tokenRaw"),
        Mapping(target = "tokenDecimals", source = "action.tokenDecimals"),
        Mapping(target = "venue", source = "action.venue"),
    )
    fun toResponse(view: WalletActionView): WalletActionResponse
}
