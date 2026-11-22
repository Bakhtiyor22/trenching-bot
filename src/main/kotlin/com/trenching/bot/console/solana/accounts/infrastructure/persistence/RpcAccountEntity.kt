package com.trenching.bot.console.solana.accounts.infrastructure.persistence

import com.trenching.bot.console.solana.accounts.domain.BalanceProblem
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

/**
 * The state of one RPC account, `primary` or `backup`: at most two rows. It holds no endpoint, key or project id. The credits are the
 * last balance read and are all null until one succeeds.
 */
@Entity
@Table(name = "rpc_accounts")
class RpcAccountEntity(
    @Id @Column(name = "name", nullable = false, length = 16) var name: String,
    @Column(name = "out_of_credits_since") var outOfCreditsSince: Instant?,
    @Column(name = "credits_remaining") var creditsRemaining: Long?,
    @Column(name = "credits_used") var creditsUsed: Long?,
    @Column(name = "credits_limit") var creditsLimit: Long?,
    @Column(name = "cycle_ends_on") var cycleEndsOn: LocalDate?,
    @Column(name = "plan", length = 32) var plan: String?,
    @Column(name = "balance_checked_at") var balanceCheckedAt: Instant?,
    @Enumerated(EnumType.STRING) @Column(name = "balance_problem", length = 32) var balanceProblem: BalanceProblem?,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
)
