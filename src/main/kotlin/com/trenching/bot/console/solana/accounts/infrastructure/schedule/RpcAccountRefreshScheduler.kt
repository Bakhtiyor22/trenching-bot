package com.trenching.bot.console.solana.accounts.infrastructure.schedule

import com.trenching.bot.console.solana.accounts.application.RpcAccountRefresh
import java.util.concurrent.TimeUnit
import org.springframework.scheduling.annotation.Scheduled

/** The balance refresh's timed trigger; the first run is half a minute after startup, so balances and the backup's network check appear soon, and runs never overlap. */
class RpcAccountRefreshScheduler(private val refresh: RpcAccountRefresh) {
    @Scheduled(fixedDelayString = REFRESH_INTERVAL, initialDelay = INITIAL_DELAY_SECONDS, timeUnit = TimeUnit.SECONDS)
    fun refreshBalances() {
        refresh.refresh()
    }

    private companion object {
        const val INITIAL_DELAY_SECONDS = 30L
        const val REFRESH_INTERVAL = "\${trenching.solana.usage.refresh-interval:15m}"
    }
}
