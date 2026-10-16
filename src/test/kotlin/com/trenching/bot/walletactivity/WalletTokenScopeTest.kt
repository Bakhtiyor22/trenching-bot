package com.trenching.bot.walletactivity

import com.trenching.bot.chaincapture.CaptureBudget
import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.chaincapture.SolanaReadRpc
import com.trenching.bot.common.Base58
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.walletactivity.application.WalletHistoryCaptureService
import com.trenching.bot.walletactivity.infrastructure.capture.VerificationStart
import com.trenching.bot.walletactivity.infrastructure.capture.WalletHistoryArtifactStore
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

class WalletTokenScopeTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()
    private val store = WalletHistoryArtifactStore(mapper)
    private val wallet = WalletAddress.of(WALLET)
    private val calls = mutableListOf<Pair<String, List<Any>>>()

    @Test
    fun `acquisition reads the mint once, lists the wallet's token account, then verifies from the mint listing in a version four manifest`() {
        val result = capture(request())

        assertEquals(listOf("getGenesisHash", "getAccountInfo", "getSignaturesForAddress", "getTransaction", "getSignaturesForAddress"), calls.map { it.first })
        assertEquals(MINT, calls[4].second[0])
        assertEquals(MINT, calls[1].second[0])
        assertEquals(TOKEN_ACCOUNT, calls[2].second[0])
        assertFalse((calls[2].second[1] as Map<*, *>).containsKey("until"))
        val manifest = result.manifest
        assertEquals(4, manifest.schemaVersion)
        assertEquals(WalletHistoryScope.WALLET_TOKEN_ACCOUNT, manifest.scope)
        assertEquals(TOKEN_ACCOUNT, manifest.listingAddress)
        assertEquals(MINT, manifest.targetMint)
        assertEquals(TOKEN_2022, manifest.tokenProgram)
        assertEquals(VerifiedFirstBuy(BUY, BUY_SLOT, 1), manifest.verification?.firstBuy)
        assertEquals(FirstBuyGap.CREATION_NOT_IDENTIFIED, manifest.verification?.result?.reason)
        assertEquals(WalletHistoryOutcome.UNCLASSIFIED, manifest.records.single().outcome)
        assertTrue(manifest.complete)
        assertEquals(manifest, store.inspect(directory).manifest)
    }

    @Test
    fun `the scope's defaults are a thousand records and twenty scan pages`() {
        assertEquals(1000, WalletHistoryCaptureLimits.TOKEN_ACCOUNT_DEFAULT_TRANSACTIONS)
        assertEquals(20, WalletHistoryCaptureLimits.TOKEN_ACCOUNT_DEFAULT_SCAN_PAGES)
    }

    @Test
    fun `a capture whose listing stops at its record limit finishes incomplete, stays so on resume and still decodes its first-buy checks`() {
        val capped = capture(request().copy(limit = 1))

        assertEquals(listOf("getGenesisHash", "getAccountInfo", "getSignaturesForAddress", "getTransaction"), calls.map { it.first })
        assertEquals(WalletHistoryDiscoveryStop.LIMIT_REACHED, capped.manifest.discoveryStop)
        assertEquals(VerificationStart.of(wallet, null), capped.manifest.verification)
        assertFalse(capped.manifest.complete)
        assertTrue(WalletHistoryCompletion.historyNotExhausted(capped.manifest))
        assertFalse(store.inspect(directory).manifest.complete)
        calls.clear()

        val resumed = capture(request().copy(limit = 1))

        assertEquals(listOf("getGenesisHash"), calls.map { it.first })
        assertFalse(resumed.manifest.complete)
        val decoded = recorded().decode(directory, wallet)
        assertFalse(decoded.complete)
        assertEquals(TokenAccountFirstBuy.NotEstablished(TokenAccountGap.HISTORY_NOT_EXHAUSTED, emptyList()), decoded.targetToken?.tokenAccountFirstBuy)
        assertEquals(ActualFirstBuy.NotVerified, decoded.targetToken?.actualFirstBuy)
    }

    @Test
    fun `a capped capture saved as complete before history exhaustion was required is read as incomplete`() {
        saveTokenCapture(listOf(buy()), discoveryStop = WalletHistoryDiscoveryStop.LIMIT_REACHED, complete = true)

        val manifest = store.inspect(directory).manifest

        assertFalse(manifest.complete)
        assertTrue(WalletHistoryCompletion.historyNotExhausted(manifest))
    }

    @Test
    fun `inspection still refuses a capture saved as complete before its acquisition finished`() {
        saveTokenCapture(listOf(buy()), discoveryStop = WalletHistoryDiscoveryStop.LIMIT_REACHED, complete = true, verification = null)

        val failure = assertFailsWith<CaptureValidationException> { store.inspect(directory) }

        assertEquals("COMPLETE_STATUS", failure.category)
    }

    @Test
    fun `a capture that listed its history to the end and finished verifying is complete`() {
        saveTokenCapture(listOf(buy()), complete = true)

        val manifest = store.inspect(directory).manifest

        assertTrue(manifest.complete)
        assertFalse(WalletHistoryCompletion.historyNotExhausted(manifest))
    }

    @Test
    fun `a mint owned by another program is refused before any listing and nothing is saved`() {
        val failure = assertFailsWith<CaptureValidationException> { capture(request(), owner = PUMP_FUN) }
        assertEquals("MINT_PROGRAM_UNSUPPORTED", failure.category)
        assertEquals(listOf("getGenesisHash", "getAccountInfo"), calls.map { it.first })
        assertFalse(Files.exists(directory.resolve("manifest.json")))
    }

    @Test
    fun `a missing mint account is refused before any listing`() {
        val failure = assertFailsWith<CaptureValidationException> { capture(request(), owner = null) }
        assertEquals("MINT_NOT_FOUND", failure.category)
        assertEquals(listOf("getGenesisHash", "getAccountInfo"), calls.map { it.first })
    }

    @Test
    fun `the target mint is required for the token scope and refused for the others before any provider request`() {
        listOf(
            request().copy(targetMint = null),
            WalletHistoryCaptureRequest(wallet, directory, targetMint = MintAddress.of(MINT)),
            WalletHistoryCaptureRequest(wallet, directory, scope = WalletHistoryScope.PUMPFUN_USER_VOLUME_ACCUMULATOR, targetMint = MintAddress.of(MINT)),
        ).forEach { request ->
            val failure = assertFailsWith<CaptureValidationException>("$request") { capture(request) }
            assertEquals("REQUEST_BOUNDS", failure.category)
        }
        assertEquals(emptyList(), calls)
    }

    @Test
    fun `resuming with another mint is refused before any provider request`() {
        capture(request())
        calls.clear()
        val failure = assertFailsWith<CaptureValidationException> { capture(request().copy(targetMint = MintAddress.of(OTHER_MINT))) }
        assertEquals("RESUME_IDENTITY", failure.category)
        assertEquals(emptyList(), calls)
    }

    @Test
    fun `resuming keeps the saved token program and never reads the mint again`() {
        val interrupted = capture(request().copy(requestBudget = 3))
        assertEquals("REQUESTS", interrupted.stopReason)
        assertEquals(WalletHistoryOutcome.PENDING, interrupted.manifest.records.single().outcome)
        calls.clear()

        val resumed = capture(request())

        assertEquals(listOf("getGenesisHash", "getTransaction", "getSignaturesForAddress"), calls.map { it.first })
        assertEquals(TOKEN_2022, resumed.manifest.tokenProgram)
        assertTrue(resumed.manifest.complete)
    }

    @Test
    fun `inspection refuses a token scope manifest whose program mint or listing address is forged`() {
        val valid = capture(request()).manifest
        mapOf(
            "the other token program" to (valid.copy(tokenProgram = TOKEN) to "LISTING_ADDRESS"),
            "an unsupported program" to (valid.copy(tokenProgram = PUMP_FUN) to "MINT_PROGRAM_UNSUPPORTED"),
            "another mint" to (valid.copy(targetMint = OTHER_MINT) to "LISTING_ADDRESS"),
            "a malformed mint" to (valid.copy(targetMint = "not-a-mint") to "MANIFEST_IDENTITY"),
            "the wallet as listing address" to (valid.copy(listingAddress = WALLET) to "LISTING_ADDRESS"),
        ).forEach { (name, forged) ->
            store.save(directory, forged.first)
            val failure = assertFailsWith<CaptureValidationException>(name) { store.inspect(directory) }
            assertEquals(forged.second, failure.category, name)
        }
    }

    @Test
    fun `version four is valid only with the token scope and the token scope only with version four`() {
        capture(request())
        val token = savedManifest(directory)
        val legacyDirectory = directory.resolve("legacy")
        capture(WalletHistoryCaptureRequest(wallet, legacyDirectory, limit = 1))
        val legacy = savedManifest(legacyDirectory)
        assertEquals(3, legacy.path("schemaVersion").asInt())
        assertFalse(legacy.has("targetMint") || legacy.has("tokenProgram") || legacy.has("verification"))
        mapOf(
            "token scope at version three" to (token.deepCopy().put("schemaVersion", 3) to "MANIFEST_FIELDS"),
            "existing scope at version four" to (legacy.deepCopy().put("schemaVersion", 4)
                .put("targetMint", MINT).put("tokenProgram", TOKEN_2022) to "MANIFEST_FIELDS"),
            "token fields at version three" to (legacy.deepCopy().put("targetMint", MINT) to "MANIFEST_FIELDS"),
            "missing token program" to (token.deepCopy().apply { remove("tokenProgram") } to "MANIFEST_STRING"),
            "a malformed verification section" to (token.deepCopy().apply { putObject("verification").put("fetches", 0) } to "MANIFEST_FIELDS"),
        ).forEach { (name, forged) ->
            writeManifest(forged.first)
            val failure = assertFailsWith<CaptureValidationException>(name) { store.inspect(directory) }
            assertEquals(forged.second, failure.category, name)
        }
    }

    @Test
    fun `a captured balance of the mint that reports another token program stops verification and is refused by inspection`() {
        val stopped = assertFailsWith<CaptureValidationException> {
            capture(request()) { buy().also { balanceOf(it, "postTokenBalances", TOKEN_ACCOUNT_INDEX).put("programId", TOKEN) } }
        }
        assertEquals("TOKEN_PROGRAM_MISMATCH", stopped.category)
        val failure = assertFailsWith<CaptureValidationException> { store.inspect(directory) }
        assertEquals("TOKEN_PROGRAM_MISMATCH", failure.category)
    }

    @Test
    fun `decoding finds the token account first buy and keeps the actual first buy a separate result`() {
        capture(request())

        val decoded = recorded().decode(directory, wallet)

        assertEquals(TargetTokenEvidence(
            mint = MintAddress.of(MINT), tokenProgram = TOKEN_2022, tokenAccount = TOKEN_ACCOUNT,
            pumpAccumulator = PUMP_ACCUMULATOR, pumpSwapAccumulator = PUMPSWAP_ACCUMULATOR,
            tokenAccountFirstBuy = TokenAccountFirstBuy.Found(TransactionSignature.of(BUY), Slot.of(BUY_SLOT), 1),
            actualFirstBuy = ActualFirstBuy.Unproven(FirstBuyGap.CREATION_NOT_IDENTIFIED, emptyList(), null),
        ), decoded.targetToken)
    }

    @Test
    fun `an earlier wallet signed undecoded record that raised the balance blocks the token account first buy`() {
        val earlier = buy(EARLIER, BUY_SLOT - 10).also(::sellDiscriminator)
        saveTokenCapture(listOf(buy(), earlier))

        val firstBuy = recorded().decode(directory, wallet).targetToken?.tokenAccountFirstBuy

        assertEquals(TokenAccountFirstBuy.NotEstablished(TokenAccountGap.EARLIER_UNDECODED_ACQUISITION, listOf(TransactionSignature.of(EARLIER))), firstBuy)
    }

    @Test
    fun `a decoded buy of another mint is an unsupported route only in the token scope`() {
        val otherMintTarget = buy().also { keys(it).addObject().put("pubkey", OTHER_TOKEN_ACCOUNT).put("signer", false).put("writable", false) }
        saveTokenCapture(listOf(otherMintTarget), mint = OTHER_MINT, tokenAccount = OTHER_TOKEN_ACCOUNT)

        val decoded = recorded().decode(directory, wallet)

        assertEquals(WalletHistoryDecodeOutcome.UNSUPPORTED_ROUTE, decoded.records.single().outcome)
        assertEquals(mapOf("OTHER_MINT" to 1), decoded.rejections)
        assertEquals(emptyList(), decoded.transactions)
        assertEquals(TokenAccountFirstBuy.NoBuy, decoded.targetToken?.tokenAccountFirstBuy)

        val legacyDirectory = directory.resolve("legacy")
        capture(WalletHistoryCaptureRequest(wallet, legacyDirectory, limit = 1))
        val legacy = recorded().decode(legacyDirectory, wallet)
        assertEquals(WalletHistoryDecodeOutcome.DECODED, legacy.records.single().outcome)
        assertEquals(null, legacy.targetToken)
    }

    private fun request() = WalletHistoryCaptureRequest(wallet, directory, limit = WalletHistoryCaptureLimits.TOKEN_ACCOUNT_DEFAULT_TRANSACTIONS,
        scanPages = WalletHistoryCaptureLimits.TOKEN_ACCOUNT_DEFAULT_SCAN_PAGES, scope = WalletHistoryScope.WALLET_TOKEN_ACCOUNT,
        targetMint = MintAddress.of(MINT))

    private fun recorded() = WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper)

    private fun capture(request: WalletHistoryCaptureRequest, owner: String? = TOKEN_2022, detail: () -> JsonNode = ::buy): WalletHistoryCaptureResult =
        WalletHistoryCaptureService(rpc(owner, detail), mapper, Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), ZoneOffset.UTC)).capture(request)

    private fun rpc(owner: String?, detail: () -> JsonNode) = SolanaReadRpc { method, params, budget: CaptureBudget ->
        calls += method to params
        budget.request()
        when (method) {
            "getGenesisHash" -> mapper.readTree("\"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d\"")
            "getAccountInfo" -> mapper.readTree(owner?.let {
                """{"context":{"slot":1},"value":{"data":["","base64"],"executable":false,"lamports":1461600,"owner":"$it"}}"""
            } ?: """{"context":{"slot":1},"value":null}""")
            "getSignaturesForAddress" -> mapper.readTree(if (params[0] in setOf(TOKEN_ACCOUNT, WALLET)) """[{"signature":"$BUY","slot":$BUY_SLOT,"err":null}]""" else "[]")
            "getTransaction" -> detail()
            else -> error("Unexpected method $method")
        }
    }

    private fun saveTokenCapture(
        transactions: List<ObjectNode>,
        mint: String = MINT,
        tokenAccount: String = TOKEN_ACCOUNT,
        discoveryStop: WalletHistoryDiscoveryStop = WalletHistoryDiscoveryStop.HISTORY_END,
        complete: Boolean = true,
        verification: WalletTokenVerification? = VerificationStart.of(wallet, null),
    ) {
        val records = transactions.map { transaction ->
            val signature = transaction.path("transaction").path("signatures").path(0).asString()
            val written = store.writeTransaction(directory, signature, transaction)
            WalletHistoryRecord(signature, transaction.path("slot").asLong(), WalletHistoryOutcome.UNCLASSIFIED, written.path, written.sha256, written.bytes)
        }
        val oldest = records.minBy { it.slot }
        store.save(directory, WalletHistoryManifest(
            schemaVersion = 4, wallet = WALLET, scope = WalletHistoryScope.WALLET_TOKEN_ACCOUNT, listingAddress = tokenAccount,
            genesisHash = "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d", commitment = "confirmed", capturedAt = "2026-10-10T00:00:00Z",
            limit = if (discoveryStop == WalletHistoryDiscoveryStop.LIMIT_REACHED) records.size else 100, scanPageLimit = 5,
            selection = records.map { WalletHistorySelection(it.signature, it.slot) }, records = records,
            complete = complete, discoveryComplete = true, discoveryStop = discoveryStop,
            scannedSignatures = records.size, skippedFailed = 0, scanCursor = WalletHistoryScanCursor(oldest.signature, oldest.slot),
            requestsUsed = records.size + 3, bytesUsed = 1000, targetMint = mint, tokenProgram = TOKEN_2022,
            verification = verification,
        ))
    }

    private fun buy(signature: String = BUY, slot: Long = BUY_SLOT): ObjectNode {
        val transaction = javaClass.getResourceAsStream("/walletactivity/decoder/pumpfun-buy.json")
            .use { mapper.readTree(requireNotNull(it)) } as ObjectNode
        (transaction.path("transaction").path("signatures") as ArrayNode).set(0, mapper.nodeFactory.stringNode(signature))
        return transaction.put("slot", slot)
    }

    private fun keys(transaction: ObjectNode): ArrayNode = transaction.path("transaction").path("message").path("accountKeys") as ArrayNode

    private fun balanceOf(transaction: ObjectNode, field: String, accountIndex: Int): ObjectNode =
        transaction.path("meta").path(field).first { it.path("accountIndex").asInt() == accountIndex } as ObjectNode

    private fun sellDiscriminator(transaction: ObjectNode) {
        val buy = transaction.path("meta").path("innerInstructions").path(0).path("instructions").path(0) as ObjectNode
        val bytes = requireNotNull(Base58.decode(buy.path("data").asString()))
        "33e685a4017f83ad".chunked(2).forEachIndexed { index, hex -> bytes[index] = hex.toInt(16).toByte() }
        buy.put("data", Base58.encode(bytes))
    }

    private fun savedManifest(captureDirectory: Path): ObjectNode =
        mapper.readTree(Files.readAllBytes(captureDirectory.resolve("manifest.json"))).path("manifest") as ObjectNode

    private fun writeManifest(inner: ObjectNode) {
        val envelope = mapper.createObjectNode().apply {
            put("sha256", MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(inner)).joinToString("") { "%02x".format(it) })
            set("manifest", inner)
        }
        Files.write(directory.resolve("manifest.json"), mapper.writeValueAsBytes(envelope))
    }

    private companion object {
        const val WALLET = "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9"

        /** The recorded buy's Token-2022 mint and the wallet's associated token account for it, at account index 3. */
        const val MINT = "5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs"
        const val TOKEN_ACCOUNT = "4CnXbpxS5UHLmeEnj9MJrAGdQCSZpDotTCAva85zQUYi"
        const val TOKEN_ACCOUNT_INDEX = 3
        const val OTHER_MINT = "hoSDHBQR236CoSLptRpDKPSU1JbcG2Ry52X1iq6pump"
        const val OTHER_TOKEN_ACCOUNT = "CKT5rabpEHRgvHU1M1DKeN1b1MhJnRr8ZLkHAajSCEmZ"
        const val PUMP_ACCUMULATOR = "Hj2hjN1EAe5pv676kUMf14zbj1ioWEHaiukbHDqw2P3D"
        const val PUMPSWAP_ACCUMULATOR = "GhhDLG4uBDVSnEAXwBpzfm4JR9E9HweSAhrCCGPjMvgp"
        const val TOKEN = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
        const val TOKEN_2022 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
        const val PUMP_FUN = "6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P"
        const val BUY = "35Vnpu9DSwADxy77FBQV8seGitMXuQRPB575AKXv1Jhh3NQSuZRKtuppUkiyLkYszxxi9HaKcmNN7fywqJ9R3mNA"
        const val BUY_SLOT = 453004996L
        val EARLIER = "1".repeat(63) + "2"
    }
}
