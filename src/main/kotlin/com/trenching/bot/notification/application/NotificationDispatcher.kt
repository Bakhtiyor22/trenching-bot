package com.trenching.bot.notification.application

import com.trenching.bot.common.ExceptionCauses
import com.trenching.bot.common.LogLine
import com.trenching.bot.notification.domain.DeliveryCategory
import com.trenching.bot.notification.domain.DeliveryDecision
import com.trenching.bot.notification.domain.DeliveryResult
import com.trenching.bot.notification.domain.NotificationRetryPolicy
import com.trenching.bot.notification.domain.PendingNotification
import com.trenching.bot.notification.mapper.NotificationPayloadCodec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory

/**
 * Sends the outbox's due messages: read them, claim each in a short transaction that counts the attempt and holds the row back by its
 * backoff, send it outside any transaction, then store the result in another short one. A run sends at most [BATCH_SIZE] messages,
 * oldest first, with a pause between sends, and stops at the first answer that asks to try later, so a Telegram outage never holds the
 * scheduling thread. After a 429 nothing is sent until the wait Telegram asked for has passed.
 *
 * Delivery is at-least-once. A claim that fails sends nothing. A result that cannot be stored after Telegram answered leaves the
 * claim in place, so the message is sent again after its backoff, and for no more than the policy's maximum attempts. A single
 * instance is assumed; two dispatchers would send twice.
 */
class NotificationDispatcher(
    private val store: NotificationOutboxStore,
    private val channel: NotificationChannel,
    private val renderer: NotificationRenderer,
    private val policy: NotificationRetryPolicy,
    private val retention: Duration,
    private val clock: Clock,
    private val pause: DispatchPause,
) {
    private val log = LoggerFactory.getLogger(NotificationDispatcher::class.java)
    private var nextPurgeAt: Instant = Instant.MIN
    private var notBefore: Instant = Instant.MIN

    fun dispatch() {
        purgeExpired()
        if (clock.instant() < notBefore) return
        store.due(clock.instant(), BATCH_SIZE).forEachIndexed { index, pending ->
            if (index > 0 && !pause.between()) return
            if (!deliver(pending)) return
        }
    }

    /** False when the run should stop because the provider asked to try later. */
    private fun deliver(pending: PendingNotification): Boolean {
        val attempts = pending.attempts + 1
        if (pending.attempts >= policy.maxAttempts) return settle(pending, pending.attempts, DeliveryDecision.Abandon(DeliveryCategory.UNEXPECTED))
        val payload = NotificationPayloadCodec.decode(pending.payload)
        if (payload == null) return settle(pending, attempts, DeliveryDecision.Abandon(DeliveryCategory.MALFORMED_PAYLOAD))
        when (claim(pending, attempts)) {
            Claim.FAILED -> return false
            Claim.TAKEN -> return true
            Claim.HELD -> Unit
        }
        val result = try {
            channel.send(renderer.render(payload))
        } catch (exception: RuntimeException) {
            // Transport errors quote the request URL, which holds the bot token: only the class is logged, never the exception.
            log.error(
                "Notification delivery failed unexpectedly and will be tried again. kind={} notificationKey={} cause={}",
                pending.kind, pending.key, ExceptionCauses.rootName(exception),
            )
            DeliveryResult.RetryLater(DeliveryCategory.UNEXPECTED, null)
        }
        val decision = policy.decide(attempts, result, clock.instant())
        if (decision is DeliveryDecision.RetryAt && decision.category == DeliveryCategory.RATE_LIMITED) notBefore = decision.at
        val stored = settle(pending, attempts, decision)
        return stored && result !is DeliveryResult.RetryLater
    }

    private fun claim(pending: PendingNotification, attempts: Int): Claim = try {
        if (store.claim(pending.id, pending.attempts, policy.claimUntil(attempts, clock.instant()))) Claim.HELD else Claim.TAKEN
    } catch (exception: RuntimeException) {
        log.error(
            "Notification could not be claimed, so nothing was sent. kind={} notificationKey={} cause={}",
            pending.kind, pending.key, ExceptionCauses.rootName(exception),
        )
        Claim.FAILED
    }

    /** False when the outcome could not be stored; the claim then keeps the row from being sent again before its backoff. */
    private fun settle(pending: PendingNotification, attempts: Int, decision: DeliveryDecision): Boolean {
        try {
            store.record(pending.id, attempts, decision, clock.instant())
        } catch (exception: RuntimeException) {
            log.error(
                "Notification outcome could not be stored; the message may be sent again after its backoff. kind={} notificationKey={} cause={}",
                pending.kind, pending.key, ExceptionCauses.rootName(exception),
            )
            return false
        }
        when (decision) {
            DeliveryDecision.Sent -> log.debug("Notification sent. kind={} notificationKey={} attempts={}", pending.kind, pending.key, attempts)
            is DeliveryDecision.RetryAt -> log.debug(
                "Notification will be tried again. kind={} notificationKey={} attempts={}/{} code={} nextAttemptAt={}",
                pending.kind, pending.key, attempts, policy.maxAttempts, decision.category, decision.at,
            )
            is DeliveryDecision.Abandon -> LogLine("Notification was not delivered and will not be tried again: {}.", decision.category.meaning)
                .field("kind", pending.kind).field("notificationKey", pending.key).field("attempts", "$attempts/${policy.maxAttempts}")
                .field("code", decision.category).warn(log)
        }
        return true
    }

    /** Once an hour at most, in bounded batches, so the table stays small and a backlog never holds the thread for long. */
    private fun purgeExpired() {
        val now = clock.instant()
        if (now < nextPurgeAt) return
        nextPurgeAt = now.plus(PURGE_INTERVAL)
        val cutoff = now.minus(retention)
        var batches = 0
        var deleted: Int
        do {
            deleted = store.deleteCreatedBefore(cutoff, PURGE_BATCH_SIZE)
            batches++
            if (deleted > 0) log.debug("Old notifications deleted. count={} cutoff={}", deleted, cutoff)
        } while (deleted == PURGE_BATCH_SIZE && batches < MAX_PURGE_BATCHES)
    }

    private enum class Claim { HELD, TAKEN, FAILED }

    companion object {
        const val BATCH_SIZE = 20
        const val PURGE_BATCH_SIZE = 500
        private const val MAX_PURGE_BATCHES = 20
        private val PURGE_INTERVAL: Duration = Duration.ofHours(1)
    }
}
