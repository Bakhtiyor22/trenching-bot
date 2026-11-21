package com.trenching.bot.console.automation.application

import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/** Runs what only a stored change may say, such as a log line, once the transaction that stores it has committed; a rollback runs nothing. */
object AfterCommit {
    fun run(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return action()
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = action()
        })
    }
}
