package com.trenching.bot.console.solana.accounts.application

import com.trenching.bot.console.solana.accounts.domain.RpcAccountState
import com.trenching.bot.notification.OutboundNotification

interface RpcAccountStore {
    fun load(): List<RpcAccountState>

    /** Stores [state] and publishes [notifications] in one transaction: a change that is not stored reports nothing. */
    fun save(state: RpcAccountState, notifications: List<OutboundNotification>)
}
