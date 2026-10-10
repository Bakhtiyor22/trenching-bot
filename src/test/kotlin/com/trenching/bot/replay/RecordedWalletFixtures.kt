package com.trenching.bot.replay

import com.trenching.bot.walletactivity.WalletHistoryDiscoveryStop
import com.trenching.bot.walletactivity.WalletHistoryManifest
import com.trenching.bot.walletactivity.WalletHistoryOutcome
import com.trenching.bot.walletactivity.WalletHistoryRecord
import com.trenching.bot.walletactivity.WalletHistoryScanCursor
import com.trenching.bot.walletactivity.WalletHistoryScope
import com.trenching.bot.walletactivity.WalletHistorySelection
import com.trenching.bot.walletactivity.infrastructure.capture.WalletHistoryArtifactStore
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/** A recorded wallet capture holding Decu's real Pump.fun buy, the one transaction the user's live capture decoded. */
internal object RecordedWalletFixtures {
    const val WALLET = "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9"
    const val SIGNATURE = "35Vnpu9DSwADxy77FBQV8seGitMXuQRPB575AKXv1Jhh3NQSuZRKtuppUkiyLkYszxxi9HaKcmNN7fywqJ9R3mNA"
    const val MINT = "5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs"
    const val SLOT = 453004996L
    private const val GENESIS = "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d"

    /** Decu's Pump.fun user volume accumulator, which the buy passes as Pump.fun account 13. */
    const val ACCUMULATOR = "Hj2hjN1EAe5pv676kUMf14zbj1ioWEHaiukbHDqw2P3D"

    /**
     * Saves the buy as [target]'s only selection; [complete] false leaves it pending, [signer] false makes it a non-signer.
     * [scope] picks the listed address; [versionTwo] writes the manifest as a capture from before scopes existed.
     */
    fun save(
        mapper: ObjectMapper,
        target: Path,
        complete: Boolean = true,
        signer: Boolean = true,
        scope: WalletHistoryScope = WalletHistoryScope.WALLET_REFERENCES,
        versionTwo: Boolean = false,
    ) {
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
        val manifest = WalletHistoryManifest(
            schemaVersion = 3, wallet = WALLET, scope = scope,
            listingAddress = if (scope == WalletHistoryScope.WALLET_REFERENCES) WALLET else ACCUMULATOR, genesisHash = GENESIS,
            commitment = "confirmed", capturedAt = "2026-10-03T00:00:00Z", limit = 100, scanPageLimit = 1,
            selection = listOf(WalletHistorySelection(SIGNATURE, SLOT)), records = listOf(record),
            complete = complete, discoveryComplete = true, discoveryStop = WalletHistoryDiscoveryStop.HISTORY_END,
            scannedSignatures = 1, skippedFailed = 0, scanCursor = WalletHistoryScanCursor(SIGNATURE, SLOT),
            requestsUsed = 3, bytesUsed = record.bytes)
        if (!versionTwo) return store.save(target, manifest)
        val inner = mapper.valueToTree<ObjectNode>(manifest).apply {
            put("schemaVersion", 2)
            remove("scope")
            remove("listingAddress")
        }
        val envelope = mapper.createObjectNode().apply {
            put("sha256", MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(inner)).joinToString("") { "%02x".format(it) })
            set("manifest", inner)
        }
        Files.write(target.resolve("manifest.json"), mapper.writeValueAsBytes(envelope))
    }
}
