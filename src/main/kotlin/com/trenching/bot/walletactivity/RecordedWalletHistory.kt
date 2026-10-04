package com.trenching.bot.walletactivity

import com.trenching.bot.common.WalletAddress
import java.nio.file.Path

interface RecordedWalletHistory {
    fun inspect(directory: Path): WalletHistoryCaptureResult

    fun decode(directory: Path, wallet: WalletAddress): WalletHistoryDecodeResult
}
