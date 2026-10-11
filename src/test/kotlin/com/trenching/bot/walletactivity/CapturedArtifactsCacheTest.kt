package com.trenching.bot.walletactivity

import com.trenching.bot.walletactivity.application.CapturedArtifacts
import com.trenching.bot.walletactivity.application.CapturedArtifactsCache
import com.trenching.bot.walletactivity.application.VerificationKinds
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.junit.jupiter.api.Test

class CapturedArtifactsCacheTest {
    private val cache = CapturedArtifactsCache()
    private var reads = 0

    @Test
    fun `an equal verified manifest reuses the last read and another manifest replaces it`() {
        val first = cache.get(manifest(requestsUsed = 1)) { read(manifest(requestsUsed = 1)) }
        val again = cache.get(manifest(requestsUsed = 1)) { read(manifest(requestsUsed = 1)) }
        assertSame(first, again)
        assertEquals(1, reads)

        cache.get(manifest(requestsUsed = 2)) { read(manifest(requestsUsed = 2)) }
        cache.get(manifest(requestsUsed = 1)) { read(manifest(requestsUsed = 1)) }
        assertEquals(3, reads)
    }

    private fun read(manifest: WalletHistoryManifest): CapturedArtifacts {
        reads++
        return CapturedArtifacts(manifest, emptyMap(), emptyMap(), VerificationKinds(), null)
    }

    private fun manifest(requestsUsed: Int) = WalletHistoryManifest(
        schemaVersion = 3, wallet = WALLET, genesisHash = "genesis", commitment = "confirmed", capturedAt = "2026-10-10T00:00:00Z",
        limit = 1, scanPageLimit = 1, selection = emptyList(), records = emptyList(), complete = true, discoveryComplete = true,
        discoveryStop = WalletHistoryDiscoveryStop.HISTORY_END, scannedSignatures = 0, skippedFailed = 0, scanCursor = null,
        requestsUsed = requestsUsed, bytesUsed = 0,
    )

    private companion object {
        const val WALLET = "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9"
    }
}
