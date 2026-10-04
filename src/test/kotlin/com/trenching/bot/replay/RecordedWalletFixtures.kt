package com.trenching.bot.replay

import com.trenching.bot.walletactivity.WalletHistoryDiscoveryStop
import com.trenching.bot.walletactivity.WalletHistoryManifest
import com.trenching.bot.walletactivity.WalletHistoryOutcome
import com.trenching.bot.walletactivity.WalletHistoryRecord
import com.trenching.bot.walletactivity.WalletHistoryScanCursor
import com.trenching.bot.walletactivity.WalletHistorySelection
import com.trenching.bot.walletactivity.infrastructure.capture.WalletHistoryArtifactStore
import java.nio.file.Path
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/** A recorded wallet capture holding Decu's real Pump.fun buy, the one transaction the user's live capture decoded. */
internal object RecordedWalletFixtures {
    const val WALLET = "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9"
    const val SIGNATURE = "35Vnpu9DSwADxy77FBQV8seGitMXuQRPB575AKXv1Jhh3NQSuZRKtuppUkiyLkYszxxi9HaKcmNN7fywqJ9R3mNA"
    const val MINT = "5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs"
    const val SLOT = 453004996L
    private const val GENESIS = "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d"

    /** Saves the buy as [target]'s only selection; [complete] false leaves it pending, [signer] false makes it a non-signer. */
    fun save(mapper: ObjectMapper, target: Path, complete: Boolean = true, signer: Boolean = true) {
        val store = WalletHistoryArtifactStore(mapper)
        val transaction = javaClass.getResourceAsStream("/walletactivity/decoder/pumpfun-buy.json").use { mapper.readTree(requireNotNull(it)) }
        val outcome = if (signer) WalletHistoryOutcome.UNCLASSIFIED else WalletHistoryOutcome.NON_SIGNER
        if (!signer) {
            (transaction.path("transaction").path("message").path("accountKeys")
                .first { it.path("pubkey").asString() == WALLET } as ObjectNode).put("signer", false)
        }
        val record = if (complete) {
            val artifact = store.writeTransaction(target, SIGNATURE, transaction)
            WalletHistoryRecord(SIGNATURE, SLOT, outcome, artifact.path, artifact.sha256, artifact.bytes)
        } else WalletHistoryRecord(SIGNATURE, SLOT, WalletHistoryOutcome.PENDING)
        store.save(target, WalletHistoryManifest(
            schemaVersion = 2, wallet = WALLET, genesisHash = GENESIS,
            commitment = "confirmed", capturedAt = "2026-10-03T00:00:00Z", limit = 100, scanPageLimit = 1,
            selection = listOf(WalletHistorySelection(SIGNATURE, SLOT)), records = listOf(record),
            complete = complete, discoveryComplete = true, discoveryStop = WalletHistoryDiscoveryStop.HISTORY_END,
            scannedSignatures = 1, skippedFailed = 0, scanCursor = WalletHistoryScanCursor(SIGNATURE, SLOT),
            requestsUsed = 3, bytesUsed = record.bytes))
    }
}
