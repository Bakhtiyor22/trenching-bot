package com.trenching.bot.console.mapper

import com.trenching.bot.console.api.dto.RunUsdtViewResponse
import com.trenching.bot.console.api.dto.SolUsdtPriceResponse
import com.trenching.bot.console.domain.RunUsdtView
import com.trenching.bot.marketdata.RateLookup
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.Mappings
import org.mapstruct.ReportingPolicy

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, uses = [ConsoleValueMapper::class],
    unmappedTargetPolicy = ReportingPolicy.ERROR)
abstract class RunUsdtViewMapper {
    abstract fun toResponse(view: RunUsdtView): RunUsdtViewResponse

    @Mappings(value = [
        Mapping(target = "rate", source = "available.rate"),
        Mapping(target = "asOf", source = "available.at"),
        Mapping(target = "source", source = "available.source"),
        Mapping(target = "unavailableReason", source = "unavailableReason"),
    ])
    protected abstract fun toPrice(available: RateLookup.Available?, unavailableReason: String?): SolUsdtPriceResponse

    /** Flattens the sealed lookup: each variant is passed as its own nullable source, as in [RunResponseMapper]. */
    fun toPrice(lookup: RateLookup): SolUsdtPriceResponse =
        toPrice(lookup as? RateLookup.Available, (lookup as? RateLookup.Unavailable)?.reason?.name)
}
