package com.trenching.bot.chaincapture

import org.slf4j.LoggerFactory

/** What one fetched batch left to apply: the completed fetches in input order, and the item whose provider failure stays retryable. */
class DetailBatch<I, R>(val completed: List<FetchedItem<I, R>>, val unavailable: I?)

/**
 * Runs one detail phase of a capture: the unresolved items in checkpoint-sized batches, each fetched in parallel, applied to the
 * capture's state and checkpointed before the next batch. A provider or budget failure, and a stop request, are recorded on the
 * [CaptureStop] after the completed fetches are applied and saved; any other failure is saved first and then rethrown.
 * [label] prefixes the log lines (for example `wallet_capture`).
 */
class DetailBatches(
    private val fetch: BoundedParallelFetch,
    private val observer: CaptureCheckpointObserver,
    private val concurrency: Int,
    private val batchSize: Int,
    private val label: String,
) {
    private val log = LoggerFactory.getLogger(DetailBatches::class.java)

    /**
     * Returns the final state. [apply] folds a batch into the state, [checkpoint] saves it, and [report] receives the summed
     * measurements once, even when the phase stops early, if at least one batch ran.
     */
    fun <I : Any, R : Any, S> run(
        items: List<I>,
        initial: S,
        budget: CaptureBudget,
        stop: CaptureStop,
        signatureOf: (I) -> String,
        fetchOne: (I) -> R,
        apply: (S, DetailBatch<I, R>) -> S,
        checkpoint: (S) -> Unit,
        report: (CaptureMeasurements) -> Unit,
    ): S {
        var state = initial
        val phase = DetailMeasurements()
        try {
            for (batch in items.chunked(batchSize)) {
                val fetched = fetch.fetch(batch, concurrency, budget, observer::stopRequested, fetchOne)
                phase.add(fetched.measurements)
                val done = fetched.completed.mapTo(HashSet()) { it.index }
                val failed = batch.withIndex().firstOrNull { (position, _) -> position !in done }?.value
                state = apply(state, DetailBatch(fetched.completed, failed.takeIf { fetched.failure is CaptureProviderException }))
                when (val failure = fetched.failure) {
                    null -> if (fetched.stopped) stop.record(CaptureCategory.STOP_REQUESTED)
                    is CaptureProviderException -> {
                        log.warn("{} interrupted category={} signature={}", label, failure.category, failed?.let(signatureOf))
                        stop.record(failure.category, failure.retryAfter)
                    }
                    is CaptureBudgetException -> {
                        log.warn("{} interrupted category={} signature={}", label, failure.category, failed?.let(signatureOf))
                        stop.record(failure.category)
                    }
                    else -> {
                        checkpoint(state)
                        throw failure
                    }
                }
                checkpoint(state)
                if (stop.reason != null) break
            }
        } finally {
            if (phase.batches > 0) report(phase.total)
        }
        return state
    }
}
