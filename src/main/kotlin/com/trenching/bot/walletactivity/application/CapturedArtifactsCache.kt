package com.trenching.bot.walletactivity.application

import com.trenching.bot.walletactivity.WalletHistoryManifest
import java.util.concurrent.atomic.AtomicReference

/**
 * The last [CapturedArtifacts] read, reused while the freshly verified manifest is equal to the one it was read for. An equal
 * manifest records the same hash for every artifact, so the artifacts and everything decoded from them are the same. One entry
 * only, so inspection followed by decoding of the same capture reads and decodes its artifacts once.
 */
internal class CapturedArtifactsCache {
    private val last = AtomicReference<CapturedArtifacts?>()

    /** [manifest] must have just been verified together with its artifacts. */
    fun get(manifest: WalletHistoryManifest, read: () -> CapturedArtifacts): CapturedArtifacts =
        last.get()?.takeIf { it.manifest == manifest } ?: read().also(last::set)
}
