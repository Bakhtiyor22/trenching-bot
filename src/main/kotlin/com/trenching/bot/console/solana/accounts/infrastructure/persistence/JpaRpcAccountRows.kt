package com.trenching.bot.console.solana.accounts.infrastructure.persistence

import com.trenching.bot.console.solana.accounts.application.RpcAccountRows
import com.trenching.bot.console.solana.accounts.domain.RpcAccountState
import org.springframework.stereotype.Component

@Component
class JpaRpcAccountRows(private val repository: RpcAccountRepository) : RpcAccountRows {
    override fun all(): List<RpcAccountState> = repository.findAll().mapNotNull(RpcAccountRecords::toState)

    override fun write(state: RpcAccountState) {
        val entity = repository.findById(state.name.label).orElse(null)
        if (entity == null) repository.save(RpcAccountRecords.newEntity(state)) else RpcAccountRecords.write(entity, state)
    }
}
