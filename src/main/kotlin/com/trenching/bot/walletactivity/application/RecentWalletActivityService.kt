package com.trenching.bot.walletactivity.application

import com.trenching.bot.apiusage.ApiUsageContext
import com.trenching.bot.chaincapture.BoundedParallelFetch
import com.trenching.bot.chaincapture.CaptureBudget
import com.trenching.bot.chaincapture.CaptureBudgetException
import com.trenching.bot.chaincapture.CaptureCategory
import com.trenching.bot.chaincapture.CaptureProviderException
import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.chaincapture.ChainCaptureFormat
import com.trenching.bot.chaincapture.ChainCaptureReads
import com.trenching.bot.chaincapture.DetailFetch
import com.trenching.bot.chaincapture.SignatureListing
import com.trenching.bot.chaincapture.SignatureListingEntry
import com.trenching.bot.chaincapture.SolanaReadRpc
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.walletactivity.WalletActivityLimits
import com.trenching.bot.walletactivity.WalletActivityLookup
import com.trenching.bot.walletactivity.WalletActivityPage
import com.trenching.bot.walletactivity.WalletActivityRequest
import com.trenching.bot.walletactivity.WalletActivityResult
import com.trenching.bot.walletactivity.WalletActivityUnavailable
import com.trenching.bot.walletactivity.domain.WalletActivitySignals
import com.trenching.bot.walletactivity.infrastructure.capture.WalletHistoryListing
import com.trenching.bot.walletactivity.infrastructure.decoder.WalletTradeEventReader
import com.trenching.bot.walletactivity.infrastructure.decoder.WalletTradeEvents
import java.time.Duration
import org.slf4j.LoggerFactory

/**
 * Reads one page of the wallet's Pump.fun user volume accumulator listing and the wallet's trades in it. A page costs the
 * listing request, at most `limit` transaction reads (cache hits are free) and, until one succeeds, the mainnet check;
 * its [CaptureBudget] allows exactly that many requests, so a retry that would exceed it ends the page as unavailable.
 */
internal class RecentWalletActivityService(
    private val rpc: SolanaReadRpc,
    private val concurrency: Int = 1,
    private val fetch: BoundedParallelFetch = BoundedParallelFetch(),
) : WalletActivityLookup {
    private val log = LoggerFactory.getLogger(RecentWalletActivityService::class.java)

    @Volatile private var mainnetVerified = false

    override fun recent(request: WalletActivityRequest, usage: ApiUsageContext): WalletActivityResult {
        val listingAddress = WalletHistoryListing.pumpAccumulator(request.wallet)
        val checkGenesis = !mainnetVerified
        val budget = budget(request.limit, checkGenesis, usage)
        val result = try {
            if (checkGenesis) requireMainnet(budget)
            WalletActivityResult.Found(page(request, listingAddress, budget))
        } catch (exception: CaptureProviderException) {
            unavailable(providerReason(exception.category), exception.retryAfter.takeIf { exception.category == CaptureCategory.RATE_LIMITED })
        } catch (_: CaptureBudgetException) {
            unavailable(if (budget.counters.rateLimitedResponses > 0) WalletActivityUnavailable.PROVIDER_RATE_LIMITED else WalletActivityUnavailable.PROVIDER_UNAVAILABLE, null)
        } catch (exception: CaptureValidationException) {
            unavailable(if (exception.category == MAINNET_REQUIRED) WalletActivityUnavailable.NOT_MAINNET else WalletActivityUnavailable.PROVIDER_UNAVAILABLE, null)
        }
        report(request, result, budget)
        return result
    }

    private fun requireMainnet(budget: CaptureBudget) {
        ChainCaptureReads.requireMainnet(rpc, budget)
        mainnetVerified = true
    }

    private fun page(request: WalletActivityRequest, listingAddress: String, budget: CaptureBudget): WalletActivityPage {
        val listingLimit = listingLimit(request.limit)
        val entries = SignatureListing.parse(ChainCaptureReads.signaturePage(rpc, listingAddress, request.before?.value, until = null, budget, listingLimit))
        if (!SignatureListing.isNewestFirst(entries, cursorSlot = null)) throw CaptureValidationException(SIGNATURE_ORDER)
        val scanned = scanned(entries, request.limit)
        val read = read(scanned.filterNot { it.failed }.distinctBy { it.signature }, request.wallet, budget)
        val actions = read.filterIsInstance<WalletTradeEvents.Read>().flatMap { it.actions }.sortedWith(WalletActivitySignals.PAGE_ORDER)
        val listingEnded = entries.size < listingLimit && scanned.size == entries.size
        val nextBefore = scanned.lastOrNull()?.takeUnless { listingEnded }?.let { TransactionSignature.of(it.signature) }
        return WalletActivityPage(request.wallet, actions, read.count { it == WalletTradeEvents.Unreadable }, scanned.size, nextBefore, budget.requestsUsed)
    }

    /** The listing entries up to and including the [limit]-th successful one, or all of them when fewer succeeded. */
    private fun scanned(entries: List<SignatureListingEntry>, limit: Int): List<SignatureListingEntry> {
        val last = entries.withIndex().filterNot { it.value.failed }.getOrNull(limit - 1)?.index ?: entries.lastIndex
        return entries.take(last + 1)
    }

    private fun read(entries: List<SignatureListingEntry>, wallet: WalletAddress, budget: CaptureBudget): List<WalletTradeEvents> {
        val fetched = fetch.fetch(entries, concurrency, budget, stopRequested = { false }) { entry ->
            when (val detail = DetailFetch.request(rpc, entry.signature, budget) { WalletTradeEventReader.read(it, entry, wallet) }) {
                is DetailFetch.Fetched -> detail.outcome
                DetailFetch.UnsupportedVersion -> WalletTradeEvents.Unreadable
            }
        }
        fetched.failure?.let { throw it }
        return fetched.completed.map { it.result }
    }

    private fun budget(limit: Int, checkGenesis: Boolean, usage: ApiUsageContext): CaptureBudget {
        val maxRequests = (if (checkGenesis) 1 else 0) + LISTING_REQUESTS + limit
        return CaptureBudget(maxRequests, maxRequests * ChainCaptureFormat.MAX_RESPONSE_BYTES, operationTimeout = WalletActivityLimits.OPERATION_TIMEOUT, usage = usage)
    }

    private fun listingLimit(limit: Int): Int =
        maxOf(limit * WalletActivityLimits.LISTING_ENTRIES_PER_TRANSACTION, WalletActivityLimits.MIN_LISTING_ENTRIES)

    private fun providerReason(category: String): WalletActivityUnavailable = when (category) {
        NOT_CONFIGURED -> WalletActivityUnavailable.RPC_NOT_CONFIGURED
        CaptureCategory.RATE_LIMITED -> WalletActivityUnavailable.PROVIDER_RATE_LIMITED
        else -> WalletActivityUnavailable.PROVIDER_UNAVAILABLE
    }

    private fun unavailable(reason: WalletActivityUnavailable, retryAfter: Duration?) = WalletActivityResult.Unavailable(reason, retryAfter)

    private fun report(request: WalletActivityRequest, result: WalletActivityResult, budget: CaptureBudget) = when (result) {
        is WalletActivityResult.Found -> log.info("wallet_activity page wallet={} limit={} scanned={} actions={} unreadable={} requests={} cacheHits={}",
            request.wallet.value, request.limit, result.page.scanned, result.page.actions.size, result.page.unreadable, budget.requestsUsed, budget.counters.cacheHits)
        is WalletActivityResult.Unavailable -> log.warn("wallet_activity unavailable wallet={} limit={} reason={} requests={}",
            request.wallet.value, request.limit, result.reason, budget.requestsUsed)
    }

    private companion object {
        const val LISTING_REQUESTS = 1
        const val NOT_CONFIGURED = "NOT_CONFIGURED"
        const val MAINNET_REQUIRED = "MAINNET_REQUIRED"
        const val SIGNATURE_ORDER = "SIGNATURE_ORDER"
    }
}
