package com.trenching.bot.console.config

import com.trenching.bot.apiusage.ApiUsageRecorder
import com.trenching.bot.chaincapture.SharedTransactionCache
import com.trenching.bot.console.application.WalletActivityCache
import com.trenching.bot.console.application.WalletActivityConsoleService
import com.trenching.bot.console.capture.CaptureFileStore
import com.trenching.bot.console.capture.CaptureTokenIndex
import com.trenching.bot.console.domain.CapturedTokens
import com.trenching.bot.walletactivity.WalletActivityLookup
import com.trenching.bot.walletactivity.WalletActivityLookups
import com.trenching.bot.walletactivity.WalletHistoryCaptureProperties
import java.time.Clock
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

/**
 * Wallet activity reads `SOLANA_RPC_URL` with the wallet capture's pacing and timeouts. The transaction cache and the
 * capture annotations are the capture console's ([CaptureConfiguration], `local & !prod`); elsewhere the lookup reads
 * uncached and no action is marked captured.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnConsoleEnabled
class WalletActivityConfiguration {
    @Bean
    fun walletActivityLookup(
        rpc: SolanaRpcProperties,
        wallet: WalletHistoryCaptureProperties,
        transactionCache: ObjectProvider<SharedTransactionCache>,
        mapper: ObjectMapper,
        clock: Clock,
        recorder: ApiUsageRecorder,
    ): WalletActivityLookup = WalletActivityLookups.create(wallet.withEndpoint(rpc.rpcUrl), mapper, clock, transactionCache.ifAvailable, recorder)

    @Bean
    fun walletActivityConsoleService(lookup: WalletActivityLookup, store: ObjectProvider<CaptureFileStore>, clock: Clock): WalletActivityConsoleService =
        WalletActivityConsoleService(lookup, WalletActivityCache(clock), store.ifAvailable?.let(::CaptureTokenIndex) ?: CapturedTokens.NONE)
}
