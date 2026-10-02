package com.trenching.bot.papertrading.domain

import com.trenching.bot.marketdata.domain.MarketObservation

sealed interface ObservationSelection {
    data class Selected(val observation: MarketObservation) : ObservationSelection
    data class Unavailable(val reason: RejectionReason) : ObservationSelection
}
