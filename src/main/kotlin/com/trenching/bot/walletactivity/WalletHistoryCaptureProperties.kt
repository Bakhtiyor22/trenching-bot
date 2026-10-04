package com.trenching.bot.walletactivity

import com.trenching.bot.chaincapture.SolanaRpcSettings
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("walletactivity.capture")
class WalletHistoryCaptureProperties(
    override val endpoint: String? = null,
    override val connectTimeout: Duration = SolanaRpcSettings.DEFAULT_CONNECT_TIMEOUT,
    override val readTimeout: Duration = SolanaRpcSettings.DEFAULT_READ_TIMEOUT,
    override val operationTimeout: Duration = SolanaRpcSettings.DEFAULT_OPERATION_TIMEOUT,
) : SolanaRpcSettings
