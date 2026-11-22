package com.trenching.bot.console.automation.infrastructure.persistence

import com.trenching.bot.console.automation.domain.VariationState
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** Every list is by `seq` and takes a [Limit]; a plan holds at most `AutomationLimits.MAX_VARIATIONS_PER_PLAN` variations. */
interface AutomationVariationRepository : JpaRepository<AutomationVariationEntity, String> {
    fun findAllByPlanIdOrderBySeqAsc(planId: String, limit: Limit): List<AutomationVariationEntity>

    fun findAllByPlanIdAndStateOrderBySeqAsc(planId: String, state: VariationState, limit: Limit): List<AutomationVariationEntity>

    /** The plan's rows in [state] under a write lock held to the end of the caller's transaction, which also serialises edits of the plan. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findAllForUpdateByPlanIdAndStateOrderBySeqAsc(planId: String, state: VariationState, limit: Limit): List<AutomationVariationEntity>

    fun findOneByPlanIdAndLabel(planId: String, label: String): AutomationVariationEntity?

    fun findOneByPlanIdAndRequestId(planId: String, requestId: String): AutomationVariationEntity?

    fun existsByPlanId(planId: String): Boolean

    /** The highest `seq` of the plan, null when it has no variation. */
    @Query("select max(v.seq) from AutomationVariationEntity v where v.planId = :planId")
    fun findMaxSeq(@Param("planId") planId: String): Int?
}
