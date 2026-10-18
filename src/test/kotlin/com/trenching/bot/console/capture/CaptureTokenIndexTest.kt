package com.trenching.bot.console.capture

import com.trenching.bot.common.WalletAddress
import com.trenching.bot.console.api.dto.CaptureInspectionResponse
import com.trenching.bot.console.api.dto.CaptureStageResponse
import com.trenching.bot.console.api.dto.CaptureWalletSettings
import com.trenching.bot.console.api.dto.CaptureWindowResponse
import com.trenching.bot.console.domain.CaptureReference
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

class CaptureTokenIndexTest {
    @TempDir lateinit var root: Path
    private lateinit var store: CaptureFileStore

    @BeforeEach
    fun open() {
        store = CaptureFileStore(CapturePathPolicy(root), JsonMapper.builder().addModule(kotlinModule()).build()).also { it.initialize() }
    }

    @AfterEach
    fun close() = store.close()

    @Test
    fun `a wallet's captures hold their target mint and every market window mint, newest capture first, by display name`() {
        store.save(record("old", "runitback", "2026-10-10T09:00:00Z", CaptureWalletSettings(DECU, "wallet-token", mint = MINT_A)), create = true)
        store.save(record("new", "runitback", "2026-10-16T20:13:00Z", CaptureWalletSettings(DECU), windows = listOf(MINT_A, MINT_B)), create = true)
        store.save(record("other", "theirs", "2026-10-16T21:00:00Z", CaptureWalletSettings(OTHER_WALLET, "wallet-token", mint = MINT_A)), create = true)
        store.save(record("attached", "attached", "2026-10-16T22:00:00Z", wallet = null), create = true)

        val captures = CaptureTokenIndex(store).capturesOf(WalletAddress.of(DECU))

        assertEquals(mapOf(
            MINT_A to listOf(CaptureReference("new", "runitback (2)"), CaptureReference("old", "runitback")),
            MINT_B to listOf(CaptureReference("new", "runitback (2)")),
        ), captures)
    }

    @Test
    fun `no capture of the wallet holds nothing`() {
        assertEquals(emptyMap(), CaptureTokenIndex(store).capturesOf(WalletAddress.of(DECU)))
    }

    private fun record(id: String, name: String, createdAt: String, wallet: CaptureWalletSettings?, windows: List<String> = emptyList()) = CaptureSessionRecord(
        id = id, requestId = id, fingerprint = id, name = name, createdAt = Instant.parse(createdAt), wallet = wallet,
        walletAttemptId = "w-$id", marketAttemptId = null, ratesAttemptId = null,
        inspection = windows.takeIf { it.isNotEmpty() }?.let(::inspection),
    )

    private fun inspection(mints: List<String>): CaptureInspectionResponse {
        val stage = CaptureStageResponse("CAPTURED", null, canStart = false, canResume = false, requiresNewAttempt = false)
        return CaptureInspectionResponse(stage, stage, stage, canCalibrate = false, canExport = false, emptyMap(), emptyMap(), emptyMap(),
            marketWindows = mints.map { CaptureWindowResponse(it, 1, 2, "COMPLETE", 1, 1, null, null) })
    }

    private companion object {
        const val DECU = "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9"
        const val OTHER_WALLET = "11111111111111111111111111111111"
        const val MINT_A = "5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs"
        const val MINT_B = "6LChGSaKeBUoZGkiNc8z3ZB2rttv7pg1XBPaQJ2rpump"
    }
}
