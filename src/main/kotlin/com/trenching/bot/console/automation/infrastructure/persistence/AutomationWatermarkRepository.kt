package com.trenching.bot.console.automation.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface AutomationWatermarkRepository : JpaRepository<AutomationWatermarkEntity, String> {
    fun findOneByWallet(wallet: String): AutomationWatermarkEntity?

    /** The wallets whose discovery is working through a catch-up; one row per wallet, so a handful. */
    @Query("select w.wallet from AutomationWatermarkEntity w where w.resumeBefore is not null")
    fun findWalletsCatchingUp(): List<String>
}
