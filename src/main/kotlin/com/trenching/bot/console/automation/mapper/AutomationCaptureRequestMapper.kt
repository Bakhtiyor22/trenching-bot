package com.trenching.bot.console.automation.mapper

import com.trenching.bot.console.api.dto.CaptureBudget
import com.trenching.bot.console.api.dto.CaptureCreateRequest
import com.trenching.bot.console.api.dto.CaptureOperationRequest
import com.trenching.bot.console.api.dto.CaptureRecoveryConfig
import com.trenching.bot.console.api.dto.CaptureWalletSettings
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.ReportingPolicy

/** The capture console requests automation sends for an item; the same item, step and attempt always give the same request. */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
interface AutomationCaptureRequestMapper {
    /** The capture form's wallet + token [defaults] for one buy's [wallet] and [mint]. */
    @Mapping(target = "wallet", source = "wallet")
    @Mapping(target = "mint", source = "mint")
    @Mapping(target = "scope", source = "defaults.scope")
    @Mapping(target = "limit", source = "defaults.limit")
    @Mapping(target = "scanPages", source = "defaults.scanPages")
    fun walletToken(wallet: String, mint: String, defaults: CaptureWalletSettings): CaptureWalletSettings

    /** A session with a generated name, in the capture console's default time zone. */
    @Mapping(target = "requestId", source = "requestId")
    @Mapping(target = "wallet", source = "wallet")
    @Mapping(target = "cutoffMode", source = "cutoffMode")
    @Mapping(target = "name", ignore = true)
    @Mapping(target = "attach", ignore = true)
    @Mapping(target = "timeZone", ignore = true)
    fun session(requestId: String, wallet: CaptureWalletSettings, cutoffMode: String): CaptureCreateRequest

    /** An operation on the session's current attempt; only wallet and market operations take [recovery]. */
    @Mapping(target = "requestId", source = "requestId")
    @Mapping(target = "step", source = "step")
    @Mapping(target = "attemptId", source = "attemptId")
    @Mapping(target = "budget", source = "budget")
    @Mapping(target = "recovery", source = "recovery")
    @Mapping(target = "scanPages", ignore = true)
    @Mapping(target = "caseId", ignore = true)
    @Mapping(target = "cutoffMode", ignore = true)
    fun operation(requestId: String, step: String, attemptId: String?, budget: CaptureBudget, recovery: CaptureRecoveryConfig?): CaptureOperationRequest
}
