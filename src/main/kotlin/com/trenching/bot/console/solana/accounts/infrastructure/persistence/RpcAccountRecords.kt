package com.trenching.bot.console.solana.accounts.infrastructure.persistence

import com.trenching.bot.console.solana.accounts.domain.RpcAccountBalance
import com.trenching.bot.console.solana.accounts.domain.RpcAccountName
import com.trenching.bot.console.solana.accounts.domain.RpcAccountState

/** Translates between a stored row and the account's state; it decides nothing. A row with a name that is no account is not an account. */
object RpcAccountRecords {
    fun toState(entity: RpcAccountEntity): RpcAccountState? {
        val name = RpcAccountName.ofLabel(entity.name) ?: return null
        return RpcAccountState(name, entity.outOfCreditsSince, balance(entity), entity.balanceCheckedAt, entity.balanceProblem, entity.updatedAt)
    }

    fun write(entity: RpcAccountEntity, state: RpcAccountState) {
        val balance = state.balance
        entity.outOfCreditsSince = state.outOfCreditsSince
        entity.creditsRemaining = balance?.creditsRemaining
        entity.creditsUsed = balance?.creditsUsed
        entity.creditsLimit = balance?.creditsLimit
        entity.cycleEndsOn = balance?.cycleEndsOn
        entity.plan = balance?.plan
        entity.balanceCheckedAt = state.balanceCheckedAt
        entity.balanceProblem = state.balanceProblem
        entity.updatedAt = state.updatedAt
    }

    fun newEntity(state: RpcAccountState): RpcAccountEntity =
        RpcAccountEntity(state.name.label, null, null, null, null, null, null, null, null, state.updatedAt).also { write(it, state) }

    private fun balance(entity: RpcAccountEntity): RpcAccountBalance? {
        val remaining = entity.creditsRemaining ?: return null
        val used = entity.creditsUsed ?: return null
        val limit = entity.creditsLimit ?: return null
        val cycleEndsOn = entity.cycleEndsOn ?: return null
        return RpcAccountBalance(remaining, used, limit, cycleEndsOn, entity.plan.orEmpty())
    }
}
