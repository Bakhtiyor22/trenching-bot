package com.trenching.bot.console.config

import com.trenching.bot.apiusage.ApiPacing
import com.trenching.bot.apiusage.ApiPacingSettings
import com.trenching.bot.apiusage.ApiProvider
import com.trenching.bot.walletactivity.WalletHistoryCaptureProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@ConditionalOnConsoleEnabled
class ApiPacingConfiguration {
    /** Wallet and market captures pace through one limiter per endpoint and are configured alike; the endpoint itself is never shown. */
    @Bean
    fun apiPacingSettings(wallet: WalletHistoryCaptureProperties): ApiPacingSettings = ApiPacingSettings {
        listOf(ApiPacing(ApiProvider.SOLANA_RPC, wallet.minRequestInterval.toMillis(), wallet.maxConcurrentRequests))
    }
}
