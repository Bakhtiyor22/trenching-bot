package com.trenching.bot.portfolio.domain

import com.trenching.bot.common.Slot
import java.time.Instant

/**
 * How far the recording provably holds every trade of a position's pair: through [slot], within the cycle's horizon. [time] is
 * a block time the recording is known to have reached by then, or null when unknown. Only this proves a sale against the
 * standing curve: no trade being recorded shows nothing unless the recording covered the moment.
 */
data class RecordedThrough(val slot: Slot, val time: Instant?)
