package com.trenching.bot.console.solana.accounts.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface RpcAccountRepository : JpaRepository<RpcAccountEntity, String>
