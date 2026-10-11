package com.trenching.bot.walletactivity

import com.trenching.bot.walletactivity.infrastructure.capture.WalletHistoryArtifactStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper

class WalletHistoryArtifactStoreTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()
    private val store = WalletHistoryArtifactStore(mapper)

    @Test
    fun `an existing scope manifest is saved as version three with exactly the legacy bytes`() {
        store.save(directory, WalletHistoryManifest(
            schemaVersion = 3, wallet = WALLET, scope = WalletHistoryScope.PUMPFUN_USER_VOLUME_ACCUMULATOR,
            listingAddress = ACCUMULATOR, genesisHash = GENESIS, commitment = "confirmed", capturedAt = "2026-10-06T00:00:00Z",
            limit = 1, scanPageLimit = 2, selection = listOf(WalletHistorySelection(SIGNATURE, 100)),
            records = listOf(WalletHistoryRecord(SIGNATURE, 100, WalletHistoryOutcome.UNAVAILABLE)), complete = true,
            discoveryComplete = true, discoveryStop = WalletHistoryDiscoveryStop.LIMIT_REACHED, scannedSignatures = 1,
            skippedFailed = 0, scanCursor = WalletHistoryScanCursor(SIGNATURE, 100), requestsUsed = 3, bytesUsed = 42,
        ))

        assertEquals(LEGACY_MANIFEST, Files.readString(directory.resolve("manifest.json")))
    }

    private companion object {
        const val WALLET = "11111111111111111111111111111111"
        const val ACCUMULATOR = "7GxTMtPtJuY11YEPk9uTXrixjW4XHidUmrWRCSL9VE94"
        const val GENESIS = "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d"
        val SIGNATURE = "1".repeat(64)
        val LEGACY_INNER = """{"schemaVersion":3,"wallet":"$WALLET","scope":"PUMPFUN_USER_VOLUME_ACCUMULATOR",""" +
            """"listingAddress":"$ACCUMULATOR","genesisHash":"$GENESIS","commitment":"confirmed",""" +
            """"capturedAt":"2026-10-06T00:00:00Z","limit":1,"scanPageLimit":2,"selection":[{"signature":"$SIGNATURE","slot":100}],""" +
            """"records":[{"signature":"$SIGNATURE","slot":100,"outcome":"UNAVAILABLE","artifact":null,"sha256":null,"bytes":0}],""" +
            """"complete":true,"discoveryComplete":true,"discoveryStop":"LIMIT_REACHED","scannedSignatures":1,"skippedFailed":0,""" +
            """"scanCursor":{"signature":"$SIGNATURE","slot":100},"requestsUsed":3,"bytesUsed":42}"""
        val LEGACY_MANIFEST = """{"sha256":"31b1cac24e9c23f5d161b5af7818cbf693f0f0f30b8a1b79059e34fa3bbb778b","manifest":$LEGACY_INNER}"""
    }
}
