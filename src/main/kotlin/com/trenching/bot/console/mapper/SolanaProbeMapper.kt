package com.trenching.bot.console.mapper

import com.trenching.bot.console.api.dto.SolanaAddressCheckResponse
import com.trenching.bot.console.api.dto.SolanaCheckResponse
import com.trenching.bot.console.api.dto.SolanaStatusResponse
import com.trenching.bot.console.solana.AddressCheckOutcome
import com.trenching.bot.console.solana.AddressProbeCheck
import com.trenching.bot.console.solana.NormalizerField
import com.trenching.bot.console.solana.ProbeCheck
import com.trenching.bot.console.solana.ProbeOutcome
import com.trenching.bot.console.solana.SolanaStatus
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.Mappings
import org.mapstruct.ReportingPolicy

/** Flattens the sealed outcomes: each variant is passed as its own nullable source so MapStruct null-guards it. */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
abstract class SolanaProbeMapper {
    @Mappings(value = [
        Mapping(target = "cluster", expression = "java(cluster(status.getLastCheck()))"),
        Mapping(target = "lastCheck", expression = "java(lastCheck(status.getLastCheck()))"),
    ])
    abstract fun toResponse(status: SolanaStatus): SolanaStatusResponse

    fun toResponse(check: ProbeCheck): SolanaCheckResponse =
        toCheckResponse(check, check.outcome as? ProbeOutcome.Ok, check.outcome as? ProbeOutcome.RpcError)

    fun toResponse(check: AddressProbeCheck): SolanaAddressCheckResponse {
        val outcome = check.outcome
        val failure = (outcome as? AddressCheckOutcome.Failed)?.failure
        return toAddressCheckResponse(
            check,
            outcome as? AddressCheckOutcome.Checked,
            outcome as? AddressCheckOutcome.NoSuccessfulTransaction,
            failure as? ProbeOutcome.RpcError,
            failure,
        )
    }

    @Mappings(value = [
        Mapping(target = "outcome", source = "check.outcome.status"),
        Mapping(target = "cached", source = "check.cached"),
        Mapping(target = "checkedAt", source = "check.checkedAt"),
        Mapping(target = "latencyMs", source = "ok.latencyMs"),
        Mapping(target = "version", source = "ok.version"),
        Mapping(target = "slot", source = "ok.slot"),
        Mapping(target = "cluster", source = "ok.cluster.label"),
        Mapping(target = "rpcErrorCode", source = "rpcError.code"),
        Mapping(target = "message", expression = "java(message(check.getOutcome()))"),
    ])
    abstract fun toCheckResponse(
        check: ProbeCheck,
        ok: ProbeOutcome.Ok?,
        rpcError: ProbeOutcome.RpcError?,
    ): SolanaCheckResponse

    @Mappings(value = [
        Mapping(target = "address", source = "check.address.value"),
        Mapping(target = "outcome", source = "check.outcome.status"),
        Mapping(target = "cached", source = "check.cached"),
        Mapping(target = "checkedAt", source = "check.checkedAt"),
        Mapping(target = "signature", source = "checked.signature"),
        Mapping(target = "slot", source = "checked.slot"),
        Mapping(target = "transactionVersion", source = "checked.transactionVersion"),
        Mapping(target = "presentFields", expression = "java(presentPaths(checked))"),
        Mapping(target = "missingFields", expression = "java(missingPaths(checked))"),
        Mapping(target = "signaturesExamined", source = "noSuccessfulTransaction.signaturesExamined"),
        Mapping(target = "rpcErrorCode", source = "rpcError.code"),
        Mapping(target = "message", expression = "java(message(failure))"),
    ])
    abstract fun toAddressCheckResponse(
        check: AddressProbeCheck,
        checked: AddressCheckOutcome.Checked?,
        noSuccessfulTransaction: AddressCheckOutcome.NoSuccessfulTransaction?,
        rpcError: ProbeOutcome.RpcError?,
        failure: ProbeOutcome.Failure?,
    ): SolanaAddressCheckResponse

    fun cluster(check: ProbeCheck?): String? = (check?.outcome as? ProbeOutcome.Ok)?.cluster?.label

    fun lastCheck(check: ProbeCheck?): SolanaCheckResponse? = check?.let { toResponse(it) }

    fun presentPaths(checked: AddressCheckOutcome.Checked?): List<String> = paths(checked?.presentFields)

    fun missingPaths(checked: AddressCheckOutcome.Checked?): List<String> = paths(checked?.missingFields)

    fun message(outcome: ProbeOutcome?): String? = when (outcome) {
        null, is ProbeOutcome.Ok, ProbeOutcome.NotConfigured, ProbeOutcome.RateLimited, ProbeOutcome.Timeout -> null
        is ProbeOutcome.RpcError -> outcome.message
        is ProbeOutcome.Unavailable -> outcome.reason
    }

    private fun paths(fields: List<NormalizerField>?): List<String> = fields.orEmpty().map { it.path }
}
