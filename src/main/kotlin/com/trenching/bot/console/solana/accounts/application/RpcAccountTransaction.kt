package com.trenching.bot.console.solana.accounts.application

import com.trenching.bot.console.solana.accounts.domain.RpcAccountState
import com.trenching.bot.notification.NotificationPublisher
import com.trenching.bot.notification.OutboundNotification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The account store's transaction boundary: a state and the messages its change causes are written together, so a change that is not
 * stored reports nothing, and a message that repeats is stored once because the publisher is idempotent on its key.
 */
@Service
class RpcAccountTransaction(
    private val rows: RpcAccountRows,
    private val publisher: NotificationPublisher,
) : RpcAccountStore {
    @Transactional(readOnly = true)
    override fun load(): List<RpcAccountState> = rows.all()

    @Transactional
    override fun save(state: RpcAccountState, notifications: List<OutboundNotification>) {
        rows.write(state)
        notifications.forEach(publisher::publish)
    }
}
