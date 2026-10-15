package com.trenching.bot.replay

import com.trenching.bot.chaincapture.CaptureCheckpoint
import com.trenching.bot.chaincapture.CaptureCheckpointObserver
import com.trenching.bot.chaincapture.SolanaReadRpc
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.walletactivity.WalletHistoryCaptureRequest
import com.trenching.bot.walletactivity.application.WalletHistoryCaptureService
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper

class CaptureCheckpointTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()
    private val clock = Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `observing wallet checkpoints preserves evidence bytes and reports operation usage`() {
        val checkpoints = mutableListOf<CaptureCheckpoint>()
        val plain = directory.resolve("plain")
        val observed = directory.resolve("observed")
        val request = WalletHistoryCaptureRequest(WalletAddress.of(RecordedWalletFixtures.WALLET), plain, limit = 1)
        service(CaptureCheckpointObserver.NONE).capture(request)
        service(CaptureCheckpointObserver { checkpoints += it }).capture(request.copy(directory = observed))
        assertContentEquals(Files.readAllBytes(plain.resolve("manifest.json")), Files.readAllBytes(observed.resolve("manifest.json")))
        assertTrue(checkpoints.size >= 2)
        assertEquals("WALLET_COMPLETE", checkpoints.last().phase)
        assertEquals(2, checkpoints.last().operationRequests)
        assertEquals(2, checkpoints.last().cumulativeRequests)
        assertEquals(0, checkpoints.last().selected)
        assertEquals(clock.instant(), checkpoints.last().at)
    }

    private fun service(observer: CaptureCheckpointObserver) = WalletHistoryCaptureService(SolanaReadRpc { method, _, budget ->
        budget.request()
        when (method) {
            "getGenesisHash" -> mapper.readTree("\"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d\"")
            "getSignaturesForAddress" -> mapper.readTree("[]")
            else -> error("Unexpected provider method")
        }
    }, mapper, clock, observer = observer)
}
