package com.trenching.bot.replay.mapper

import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.replay.domain.DatasetEntry
import com.trenching.bot.replay.domain.DatasetSummary
import com.trenching.bot.replay.infrastructure.persistence.ReplayDatasetEntity
import com.trenching.bot.replay.infrastructure.persistence.ReplayDatasetSummaryRow
import java.time.Instant
import org.mapstruct.*

/** Pairs are stored as `base/quote` joined by `,`; base58 contains neither character. */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
abstract class ReplayDatasetMapper {
    abstract fun summary(id: String, name: String, trackedWallet: WalletAddress, schemaVersion: Int, transactionCount: Int,
                         observationCount: Int, firstSlot: Slot?, lastSlot: Slot?, pairs: List<MarketPair>,
                         createdAt: Instant): DatasetSummary

    abstract fun entry(summary: DatasetSummary, snapshot: String): DatasetEntry

    @Mappings(value = [
        Mapping(target = ".", source = "summary"),
        Mapping(target = "snapshot", source = "snapshot"),
        Mapping(target = "version", ignore = true),
    ])
    abstract fun toEntity(entry: DatasetEntry): ReplayDatasetEntity

    abstract fun toSummary(row: ReplayDatasetSummaryRow): DatasetSummary

    fun wallet(value: String): WalletAddress = WalletAddress.of(value)
    fun wallet(value: WalletAddress): String = value.value
    fun slot(value: Long?): Slot? = value?.let(Slot::of)
    fun slot(value: Slot?): Long? = value?.value

    fun pairs(value: String): List<MarketPair> = value.split(PAIR_SEPARATOR).filter { it.isNotEmpty() }.map { encoded ->
        val (base, quote) = encoded.split(MINT_SEPARATOR)
        MarketPair.of(MintAddress.of(base), MintAddress.of(quote))
    }

    fun pairs(value: List<MarketPair>): String =
        value.joinToString(PAIR_SEPARATOR) { it.base.value + MINT_SEPARATOR + it.quote.value }

    private companion object {
        const val PAIR_SEPARATOR = ","
        const val MINT_SEPARATOR = "/"
    }
}
