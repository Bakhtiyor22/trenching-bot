package com.trenching.bot.marketdata

import com.trenching.bot.chaincapture.SolanaRpcSettings
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("marketdata.capture")
class MarketHistoryCaptureProperties(
    override val endpoint: String? = null,
    override val connectTimeout: Duration = SolanaRpcSettings.DEFAULT_CONNECT_TIMEOUT,
    override val readTimeout: Duration = SolanaRpcSettings.DEFAULT_READ_TIMEOUT,
    override val operationTimeout: Duration = SolanaRpcSettings.DEFAULT_OPERATION_TIMEOUT,
) : SolanaRpcSettings
