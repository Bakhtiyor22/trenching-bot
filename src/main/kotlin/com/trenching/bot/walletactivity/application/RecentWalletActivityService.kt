package com.trenching.bot.walletactivity.application

import com.trenching.bot.apiusage.ApiUsageContext
import com.trenching.bot.chaincapture.CaptureBudget
import com.trenching.bot.chaincapture.CaptureBudgetException
import com.trenching.bot.chaincapture.CaptureCategory
import com.trenching.bot.chaincapture.CaptureProviderException
import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.chaincapture.CapturedTransactionCheck
import com.trenching.bot.chaincapture.CapturedTransactionShape
import com.trenching.bot.chaincapture.ChainCaptureFormat
import com.trenching.bot.chaincapture.ChainCaptureReads
import com.trenching.bot.chaincapture.SignatureListing
import com.trenching.bot.chaincapture.SignatureListingEntry
import com.trenching.bot.chaincapture.SolanaReadRpc
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.walletactivity.WalletAction
import com.trenching.bot.walletactivity.WalletActivityLimits
import com.trenching.bot.walletactivity.WalletActivityLookup
import com.trenching.bot.walletactivity.WalletActivityPage
import com.trenching.bot.walletactivity.WalletActivityRequest
import com.trenching.bot.walletactivity.WalletActivityResult
import com.trenching.bot.walletactivity.WalletActivityUnavailable
import com.trenching.bot.walletactivity.infrastructure.capture.WalletHistoryListing
import com.trenching.bot.walletactivity.infrastructure.decoder.WalletTradeEventReader
import com.trenching.bot.walletactivity.infrastructure.decoder.WalletTradeReading
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

/**
 * Lists the wallet's Pump.fun user volume accumulator, newest first, and reads up to `limit` of its successful transactions.
 * Failed listing entries are counted but never fetched. [rpc] is built per lookup, as captures build theirs per operation, so
 * an invalid endpoint fails the lookup and not startup. The endpoint's genesis hash is checked once per process.
 */
class RecentWalletActivityService(private val rpcConfigured: Boolean, private val rpc: () -> SolanaReadRpc) : WalletActivityLookup {
    private val log = LoggerFactory.getLogger(RecentWalletActivityService::class.java)
    private val mainnetChecked = AtomicBoolean(false)

    override fun recent(request: WalletActivityRequest, usage: ApiUsageContext): WalletActivityResult {
        require(WalletActivityLimits.isValidLimit(request.limit)) { "Wallet activity limit out of range limit=${request.limit}" }
        if (!rpcConfigured) return unavailable(request, WalletActivityUnavailable.RPC_NOT_CONFIGURED, null, NOT_CONFIGURED)
        val budget = CaptureBudget(WalletActivityLimits.maxRequests(request.limit), WalletActivityLimits.MAX_BYTES,
            operationTimeout = WalletActivityLimits.OPERATION_TIMEOUT, usage = usage)
        return try {
            WalletActivityResult.Found(page(rpc(), request, budget)).also { found ->
                log.info("wallet_activity listed wallet={} actions={} unreadable={} scanned={} requestsUsed={}",
                    request.wallet.value, found.page.actions.size, found.page.unreadable, found.page.scanned, found.page.requestsUsed)
            }
        } catch (exception: CaptureProviderException) {
            unavailable(request, providerReason(exception.category), exception.retryAfter, exception.category)
        } catch (exception: CaptureValidationException) {
            unavailable(request, validationReason(exception.category), null, exception.category)
        } catch (exception: CaptureBudgetException) {
            unavailable(request, WalletActivityUnavailable.PROVIDER_UNAVAILABLE, null, exception.category)
        }
    }

    private fun page(rpc: SolanaReadRpc, request: WalletActivityRequest, budget: CaptureBudget): WalletActivityPage {
        if (!mainnetChecked.get()) {
            ChainCaptureReads.requireMainnet(rpc, budget)
            mainnetChecked.set(true)
        }
        val listingLimit = WalletActivityLimits.listingEntries(request.limit)
        val entries = listing(rpc, request, listingLimit, budget)
        val consumed = consumed(entries, request.limit)
        val readings = consumed.filterNot { it.failed }.map { read(rpc, it, request, budget) }
        val actions = readings.filterIsInstance<WalletTradeReading.Read>().flatMap { it.actions }.sortedWith(NEWEST_FIRST)
        val more = consumed.size < entries.size || entries.size == listingLimit
        return WalletActivityPage(
            wallet = request.wallet,
            actions = actions,
            unreadable = readings.count { it is WalletTradeReading.Unreadable },
            scanned = consumed.size,
            nextBefore = consumed.lastOrNull()?.takeIf { more }?.let { TransactionSignature.of(it.signature) },
            requestsUsed = budget.requestsUsed,
        )
    }

    private fun listing(rpc: SolanaReadRpc, request: WalletActivityRequest, limit: Int, budget: CaptureBudget): List<SignatureListingEntry> {
        val options = mutableMapOf<String, Any>(COMMITMENT to ChainCaptureFormat.COMMITMENT, LIMIT to limit)
        request.before?.let { options[BEFORE] = it.value }
        val page = rpc.call(ChainCaptureFormat.GET_SIGNATURES, listOf(WalletHistoryListing.pumpAccumulator(request.wallet), options), budget)
        val entries = SignatureListing.parse(page)
        if (entries.size > limit || !SignatureListing.isNewestFirst(entries, null)) throw CaptureValidationException("SIGNATURE_PAGE")
        return entries
    }

    /** The listing entries up to and including the [limit]th successful one. */
    private fun consumed(entries: List<SignatureListingEntry>, limit: Int): List<SignatureListingEntry> {
        val last = entries.withIndex().filterNot { it.value.failed }.getOrNull(limit - 1)?.index ?: return entries
        return entries.subList(0, last + 1)
    }

    private fun read(rpc: SolanaReadRpc, entry: SignatureListingEntry, request: WalletActivityRequest, budget: CaptureBudget): WalletTradeReading {
        val transaction = ChainCaptureReads.transaction(rpc, entry.signature, budget)
        return when (CapturedTransactionCheck.shape(transaction, entry.signature, entry.slot)) {
            CapturedTransactionShape.VALID -> WalletTradeEventReader.read(transaction, TransactionSignature.of(entry.signature), request.wallet)
            CapturedTransactionShape.UNAVAILABLE, CapturedTransactionShape.UNSUPPORTED_VERSION, CapturedTransactionShape.INVALID -> WalletTradeReading.Unreadable
        }
    }

    private fun providerReason(category: String): WalletActivityUnavailable = when (category) {
        CaptureCategory.RATE_LIMITED -> WalletActivityUnavailable.PROVIDER_RATE_LIMITED
        NOT_CONFIGURED -> WalletActivityUnavailable.RPC_NOT_CONFIGURED
        else -> WalletActivityUnavailable.PROVIDER_UNAVAILABLE
    }

    /** An endpoint that is not a valid URL counts as not configured; any other validation failure is a reply the provider got wrong. */
    private fun validationReason(category: String): WalletActivityUnavailable = when {
        category == MAINNET_REQUIRED -> WalletActivityUnavailable.NOT_MAINNET
        category.startsWith(ENDPOINT) -> WalletActivityUnavailable.RPC_NOT_CONFIGURED
        else -> WalletActivityUnavailable.PROVIDER_UNAVAILABLE
    }

    private fun unavailable(request: WalletActivityRequest, reason: WalletActivityUnavailable, retryAfter: Duration?, category: String): WalletActivityResult {
        log.warn("wallet_activity unavailable wallet={} reason={} category={} retryAfterSeconds={}", request.wallet.value, reason, category, retryAfter?.seconds)
        return WalletActivityResult.Unavailable(reason, retryAfter)
    }

    private companion object {
        const val NOT_CONFIGURED = "NOT_CONFIGURED"
        const val MAINNET_REQUIRED = "MAINNET_REQUIRED"
        const val ENDPOINT = "ENDPOINT"
        const val COMMITMENT = "commitment"
        const val LIMIT = "limit"
        const val BEFORE = "before"
        val NEWEST_FIRST: Comparator<WalletAction> = compareByDescending<WalletAction> { it.slot }
            .thenBy { it.signature.value }
            .thenBy { it.instructionIndex }
            .thenBy(nullsFirst()) { it.innerIndex }
    }
}
