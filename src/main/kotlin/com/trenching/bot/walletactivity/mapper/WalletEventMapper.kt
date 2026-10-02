package com.trenching.bot.walletactivity.mapper

import com.trenching.bot.common.*
import com.trenching.bot.walletactivity.domain.*
import com.trenching.bot.walletactivity.infrastructure.persistence.WalletEventEntity
import org.mapstruct.*

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, uses = [ChainValueMapper::class],
    unmappedTargetPolicy = ReportingPolicy.ERROR)
abstract class WalletEventMapper {
    @Mappings(value = [
        Mapping(target = "id", expression = "java(storageId(runId, transaction))"),
        Mapping(target = "runId", source = "runId"),
        Mapping(target = "eventId", expression = "java(eventId(transaction))"),
        Mapping(target = "trackedWallet", source = "trackedWallet.value"),
        Mapping(target = "wallet", source = "transaction.wallet.value"),
        Mapping(target = "signature", source = "transaction.signature.value"),
        Mapping(target = "instructionIndex", source = "transaction.instructionIndex"),
        Mapping(target = "slot", source = "transaction.slot.value"),
        Mapping(target = "blockTime", source = "transaction.blockTime"),
        Mapping(target = "baseMint", source = "transaction.pair.base.value"),
        Mapping(target = "quoteMint", source = "transaction.pair.quote.value"),
        Mapping(target = "baseDelta", source = "transaction.baseDelta"),
        Mapping(target = "quoteDelta", source = "transaction.quoteDelta"),
        Mapping(target = "baseDecimals", source = "transaction.baseDecimals"),
        Mapping(target = "quoteDecimals", source = "transaction.quoteDecimals"),
        Mapping(target = "successful", source = "transaction.successful"),
        Mapping(target = "supportedRoute", source = "transaction.supportedRoute"),
        Mapping(target = "signer", source = "transaction.signer"),
        Mapping(target = "source", source = "transaction.source"),
        Mapping(target = "commitment", source = "transaction.commitment"),
        Mapping(target = "side", expression = "java(side(outcome))"),
        Mapping(target = "ignoredReason", expression = "java(ignoredReason(outcome))"),
        Mapping(target = "version", ignore = true),
    ])
    abstract fun toEntity(runId: String, trackedWallet: WalletAddress, transaction: WalletTransaction,
                          outcome: NormalizationOutcome): WalletEventEntity

    @Mapping(target = "pair", expression = "java(pair(entity))")
    abstract fun toTransaction(entity: WalletEventEntity): WalletTransaction

    @Mappings(value = [
        Mapping(target = "id", source = "eventId"),
        Mapping(target = "pair", expression = "java(pair(entity))"),
        Mapping(target = "baseAmount", expression = "java(baseAmount(entity))"),
        Mapping(target = "quoteAmount", expression = "java(quoteAmount(entity))"),
    ])
    abstract fun toTrade(entity: WalletEventEntity): WalletTradeObserved

    @Mappings(value = [
        Mapping(target = "sourceEventId", source = "eventId"),
        Mapping(target = "reason", source = "ignoredReason"),
    ])
    abstract fun toIgnored(entity: WalletEventEntity): NormalizationOutcome.Ignored

    fun eventId(transaction: WalletTransaction): String = WalletEventIdentity.of(transaction.signature, transaction.instructionIndex)
    fun storageId(runId: String, transaction: WalletTransaction): String = RecordIdentity.of(runId, eventId(transaction)).value
    fun pair(entity: WalletEventEntity): MarketPair = ChainValueMapper.pair(entity.baseMint, entity.quoteMint)
    fun baseAmount(entity: WalletEventEntity): TokenAmount = ChainValueMapper.amount(entity.baseDelta, entity.baseDecimals)
    fun quoteAmount(entity: WalletEventEntity): TokenAmount = ChainValueMapper.amount(entity.quoteDelta, entity.quoteDecimals)
    fun side(outcome: NormalizationOutcome): TradeSide? = when (outcome) {
        is NormalizationOutcome.Accepted -> outcome.trade.side
        is NormalizationOutcome.Ignored -> null
    }
    fun ignoredReason(outcome: NormalizationOutcome): IngestionReason? = when (outcome) {
        is NormalizationOutcome.Accepted -> null
        is NormalizationOutcome.Ignored -> outcome.reason
    }
}
