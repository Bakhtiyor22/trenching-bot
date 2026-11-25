package com.trenching.bot.replay.infrastructure.persistence

import com.trenching.bot.replay.application.StoredInstants
import com.trenching.bot.replay.domain.RunSearchQuery
import com.trenching.bot.replay.domain.RunStatus
import java.time.Instant

internal data class SqlStatement(val sql: String, val parameters: Map<String, Any>)

/**
 * The SQL of one [RunSearchQuery]. Only the filters that are set appear in it, so no parameter is ever null. The status
 * expression is the SQL form of [RunStatus.of] and a test holds the two together.
 */
internal class RunSearchSql(private val query: RunSearchQuery) {
    /** The ids of the requested page, newest first, in the order every page of one snapshot is cut from. */
    fun pageIds(): SqlStatement {
        val filter = filter(withStatuses = true)
        return SqlStatement(
            "select r.id $FROM where ${filter.sql} order by r.created_at desc, r.id asc limit :limit offset :offset",
            filter.parameters + mapOf("limit" to query.size, "offset" to query.page.toLong() * query.size),
        )
    }

    /** Runs and their realized P&L per status and quote mint under every filter but the status itself. */
    fun statusAggregates(): SqlStatement {
        val filter = filter(withStatuses = false)
        return SqlStatement(
            "select $STATUS as status, r.quote_mint, count(*), sum(r.realized_pnl) $FROM where ${filter.sql} group by $STATUS, r.quote_mint",
            filter.parameters,
        )
    }

    private fun filter(withStatuses: Boolean): SqlStatement {
        val conditions = mutableListOf("r.created_at <= :asOf")
        val parameters = mutableMapOf<String, Any>("asOf" to StoredInstants.floor(query.asOf))
        if (withStatuses && query.statuses.isNotEmpty()) {
            conditions += "$STATUS in (:statuses)"
            parameters["statuses"] = query.statuses.map { it.name }
        }
        query.mint?.let {
            conditions += "exists (select 1 from simulation_run_tokens t where t.run_id = r.id and t.mint = :mint)"
            parameters["mint"] = it.value
        }
        range("r.anchor_block_time", "anchorFrom", query.anchorFrom, "anchorTo", query.anchorTo, conditions, parameters)
        range("r.created_at", "createdFrom", query.createdFrom, "createdTo", query.createdTo, conditions, parameters)
        query.datasetId?.let {
            conditions += "r.dataset_checksum = :datasetId"
            parameters["datasetId"] = it
        }
        query.text?.let { text ->
            val clauses = mutableListOf("d.name ilike :contains escape '!'")
            parameters["contains"] = LikePatterns.contains(text)
            if (text.length >= RunSearchQuery.MIN_PREFIX_LENGTH) {
                clauses += listOf("r.id", "r.dataset_checksum", "r.configuration_hash").map { "$it like :prefix escape '!'" }
                parameters["prefix"] = LikePatterns.prefix(text)
            }
            conditions += clauses.joinToString(" or ", "(", ")")
        }
        return SqlStatement(conditions.joinToString(" and "), parameters)
    }

    private fun range(
        column: String, fromName: String, from: Instant?, toName: String, to: Instant?,
        conditions: MutableList<String>, parameters: MutableMap<String, Any>,
    ) {
        from?.let {
            conditions += "$column >= :$fromName"
            parameters[fromName] = StoredInstants.ceiling(it)
        }
        to?.let {
            conditions += "$column < :$toName"
            parameters[toName] = StoredInstants.ceiling(it)
        }
    }

    private companion object {
        const val FROM = "from simulation_runs r left join replay_datasets d on d.id = r.dataset_checksum"
        val STATUS = "case when r.filled_count = 0 then '${RunStatus.NO_ENTRY}' when r.open_count > 0 then '${RunStatus.OPEN}' " +
            "when r.realized_pnl > 0 then '${RunStatus.PROFIT}' when r.realized_pnl < 0 then '${RunStatus.LOSS}' " +
            "else '${RunStatus.FLAT}' end"
    }
}
