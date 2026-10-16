package com.trenching.bot.console.mapper

import com.trenching.bot.console.api.dto.DecimalRangeResponse
import com.trenching.bot.console.domain.NumberRange
import com.trenching.bot.console.domain.PercentConversion
import org.mapstruct.Named

/** Percent twins of basis point values, selected by name so no other integer is ever mapped through them. */
object PercentMapper {
    const val PERCENT = "bpsToPercent"
    const val PERCENT_RANGE = "bpsRangeToPercentRange"

    @JvmStatic
    @Named(PERCENT)
    fun percent(bps: Int): String = PercentConversion.toPercent(bps.toLong())

    @JvmStatic
    @Named(PERCENT_RANGE)
    fun percentRange(range: NumberRange): DecimalRangeResponse = DecimalRangeResponse(PercentConversion.toPercent(range.min),
        PercentConversion.toPercent(requireNotNull(range.max) { "A level range is bounded above range=$range" }))
}
