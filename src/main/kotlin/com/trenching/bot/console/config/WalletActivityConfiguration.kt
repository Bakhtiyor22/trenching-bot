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
 * capture annotations exist only where the capture console does (`local & !prod`); elsewhere the lookup reads uncached
 * and no action is marked captured.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnConsoleEnabled
class WalletActivityConfiguration {
    @Bean
    fun walletActivityLookup(
        rpc: SolanaRpcProperties,
        wallet: WalletHistoryCaptureProperties,
        capture: ObjectProvider<CaptureProperties>,
        mapper: ObjectMapper,
        clock: Clock,
        recorder: ApiUsageRecorder,
    ): WalletActivityLookup {
        val transactionCache = capture.ifAvailable?.let { SharedTransactionCache(it.validatedRoot().resolve(TRANSACTION_CACHE_DIRECTORY), mapper) }
        return WalletActivityLookups.create(wallet.withEndpoint(rpc.rpcUrl), mapper, clock, transactionCache, recorder)
    }

    @Bean
    fun walletActivityConsoleService(lookup: WalletActivityLookup, store: ObjectProvider<CaptureFileStore>, clock: Clock): WalletActivityConsoleService =
        WalletActivityConsoleService(lookup, WalletActivityCache(clock), store.ifAvailable?.let(::CaptureTokenIndex) ?: CapturedTokens.NONE)

    private companion object {
        /** The directory `CaptureConfiguration` gives the captures' shared transaction cache, so both read the same entries. */
        const val TRANSACTION_CACHE_DIRECTORY = "transaction-cache"
    }
}
