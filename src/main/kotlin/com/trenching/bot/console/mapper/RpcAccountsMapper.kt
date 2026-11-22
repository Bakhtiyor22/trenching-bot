package com.trenching.bot.console.mapper

import com.trenching.bot.console.api.dto.RpcAccountResponse
import com.trenching.bot.console.api.dto.RpcAccountsResponse
import com.trenching.bot.console.solana.accounts.application.RpcAccountStatus
import com.trenching.bot.console.solana.accounts.application.RpcAccountsSnapshot
import com.trenching.bot.console.solana.accounts.domain.RpcAccountName
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.Mappings
import org.mapstruct.ReportingPolicy

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
abstract class RpcAccountsMapper {
    @Mapping(target = "active", expression = "java(label(snapshot.getActive()))")
    abstract fun toResponse(snapshot: RpcAccountsSnapshot): RpcAccountsResponse

    @Mappings(value = [
        Mapping(target = "name", expression = "java(label(status.getName()))"),
        Mapping(target = "configured", expression = "java(status.getStanding() != com.trenching.bot.console.solana.accounts.application.RpcAccountStanding.NOT_CONFIGURED)"),
        Mapping(target = "state", source = "status.standing"),
        Mapping(target = "host", source = "status.host"),
        Mapping(target = "creditsRemaining", source = "status.state.balance.creditsRemaining"),
        Mapping(target = "creditsUsed", source = "status.state.balance.creditsUsed"),
        Mapping(target = "creditsLimit", source = "status.state.balance.creditsLimit"),
        Mapping(target = "cycleEndsOn", source = "status.state.balance.cycleEndsOn"),
        Mapping(target = "plan", source = "status.state.balance.plan"),
        Mapping(target = "balanceCheckedAt", source = "status.state.balanceCheckedAt"),
        Mapping(target = "balanceProblem", source = "status.state.balanceProblem"),
        Mapping(target = "outOfCreditsSince", source = "status.state.outOfCreditsSince"),
    ])
    abstract fun toResponse(status: RpcAccountStatus): RpcAccountResponse

    fun label(name: RpcAccountName?): String? = name?.label
}
