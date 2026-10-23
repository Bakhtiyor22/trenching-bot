package com.trenching.bot.replay

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.marketdata.MarketCaptureWindowParameters
import com.trenching.bot.marketdata.MarketCaseId
import com.trenching.bot.marketdata.MarketHistoryCaptureConfiguration
import com.trenching.bot.marketdata.MarketHistoryCaptureResult
import com.trenching.bot.marketdata.MarketHistoryDecodeResult
import com.trenching.bot.marketdata.MarketObservationExclusion
import com.trenching.bot.marketdata.MarketTransactionOutcome
import com.trenching.bot.marketdata.MarketTransactionRecord
import com.trenching.bot.marketdata.MarketWindowCoverage
import com.trenching.bot.marketdata.MarketWindowCutoff
import com.trenching.bot.marketdata.RecordedMarketHistory
import com.trenching.bot.marketdata.domain.CurveState
import com.trenching.bot.marketdata.infrastructure.capture.MarketHistoryArtifactStore
import com.trenching.bot.protocol.pumpfun.CurveReserves
import com.trenching.bot.protocol.pumpfun.PumpFunInstruction
import com.trenching.bot.protocol.pumpfun.PumpFunInstructionReader
import com.trenching.bot.replay.RecordedWalletFixtures.MINT
import com.trenching.bot.replay.RecordedWalletFixtures.SIGNATURE
import com.trenching.bot.replay.RecordedWalletFixtures.SLOT
import com.trenching.bot.replay.RecordedWalletFixtures.WALLET
import com.trenching.bot.replay.application.DatasetFingerprint
import com.trenching.bot.replay.application.RecordedReplayExportService
import com.trenching.bot.replay.application.ReplayRunner
import com.trenching.bot.marketdata.domain.ObservationSignature
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.replay.domain.RecordedReplayExportRequest
import com.trenching.bot.replay.domain.MarketCoverage
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.application.RecordedCaseReplayExportService
import com.trenching.bot.replay.application.CaptureCaseInspectionService
import com.trenching.bot.replay.application.RecordedWalletPlanning
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.marketdata.RecordedReferenceRates
import com.trenching.bot.marketdata.ReferenceRate
import com.trenching.bot.marketdata.ReferenceRateCaptureResult
import com.trenching.bot.walletactivity.WalletHistoryCaptureConfiguration
import com.trenching.bot.walletactivity.RecordedWalletHistory
import java.time.Instant
import com.trenching.bot.walletactivity.WalletHistoryScope
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper

class RecordedReplayExportTest {
    @TempDir lateinit var directory: Path
    private val mapper = JsonMapper.builder().build()
    private var marketDecodes = 0

    @Test
    fun `exports the real recorded buy, its in-window trade and that trade's rate as canonical v5 json, with the trade's actor and the full window's coverage, that the loader round-trips`() {
        RecordedWalletFixtures.save(mapper, wallet())
        saveRealMarketCapture()

        val export = service().export(request())

        val json = Files.readString(output())
        val dataset = Files.newInputStream(output()).use { ReplayDatasetLoader(mapper).load(it) }
        assertEquals(WalletAddress.of(WALLET), dataset.trackedWallet)
        val buy = dataset.transactions.single()
        assertEquals(TransactionSignature.of(SIGNATURE), buy.signature)
        assertEquals(BigInteger("1238520682656"), buy.baseDelta)
        assertEquals(BigInteger("-244444444"), buy.quoteDelta)
        assertEquals("pumpfun-bonding-curve-buy/v1", buy.source)
        val observation = dataset.observations.single()
        assertEquals(Slot.of(SLOT + 1), observation.slot)
        assertEquals(BigDecimal("0.00000019870174082519143559411678139870"), observation.price)
        assertEquals("pumpfun-bonding-curve-trade/v1:$TRADE:4:15:$MINT", observation.id)
        assertEquals(false, observation.curveComplete)
        assertEquals(CurveState(
            CurveReserves(BigInteger("80123104314"), BigInteger("401756777019139"), BigInteger("50123104314"), BigInteger("121856777019139")),
            feeBps = 95, creatorFeeBps = 30), observation.curve)
        assertEquals(listOf(RATE), dataset.referenceRates)
        assertEquals(ReplayDataset.SCHEMA_V5, dataset.schemaVersion)
        assertEquals(listOf(MarketCoverage.of(observation.pair, Slot.of(SLOT + 1), Slot.of(SLOT + 4500))), dataset.coverage)
        assertEquals(fixtureTrade().user.value, observation.trade?.trader)
        assertEquals(fixtureTrade().solAmount, observation.trade?.solLamports)
        assertEquals(fixtureTrade().tokenAmount, observation.trade?.tokenRaw)
        assertEquals(DatasetFingerprint.checksum(dataset), export.checksum)
        assertEquals(export.checksum, mapper.readTree(json).path("checksum").asString())
        assertEquals(listOf(1, 1, 1, 1), listOf(export.sourceBuys, export.windows, export.transactions, export.observations))
        assertEquals(output(), export.output)
        assertEquals(WalletAddress.of(WALLET), export.trackedWallet)
        assertTrue(export.written)
    }

    @Test
    fun `case export carries exact wallet anchor provenance and only the selected case data`() {
        RecordedWalletFixtures.save(mapper, wallet())
        saveRealMarketCapture()
        val window = window(SLOT + 1, SLOT + 4500)
        val planning = com.trenching.bot.replay.application.RecordedWalletPlanning(
            WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper))
        val planned = planning.plan(wallet(), MarketCaptureWindowParameters.LEGACY_LEAD_SLOTS)
        val anchor = planning.anchor(planned, window)
        val recordedMarkets = MarketHistoryCaptureConfiguration().recordedMarketHistory(mapper)
        val recordedRates = object : RecordedReferenceRates {
            override fun inspect(directory: Path): ReferenceRateCaptureResult = error("Export reads rates, not their audit")
            override fun rates(directory: Path): List<ReferenceRate> = listOf(RATE)
        }
        val request = CaptureCaseExportRequest(wallet(), markets(), rates(), MarketCaseId.from(window), WalletAddress.of(WALLET), MintAddress.of(MINT),
            anchor.signature, anchor.slot.value, anchor.instructionIndex, window.fromSlot.value, window.toSlot.value,
            ReplayMarketFixtures.PARAMETERS, directory.resolve("case.json"))

        val result = RecordedCaseReplayExportService(
            WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper), recordedMarkets, recordedRates, mapper,
        ).export(request)
        val dataset = Files.newInputStream(result.output).use { ReplayDatasetLoader(mapper).load(it) }

        assertEquals(MarketCaseId.from(window), result.caseId)
        assertEquals(WALLET, result.trackedWallet.value)
        assertEquals(MINT, result.mint.value)
        assertEquals(SIGNATURE, result.anchorSignature.value)
        assertEquals(SLOT, result.anchorSlot)
        assertEquals(dataset.transactions.single().instructionIndex, result.instructionIndex)
        assertEquals(window.fromSlot.value, result.fromSlot)
        assertEquals(window.toSlot.value, result.toSlot)
        assertEquals(ReplayMarketFixtures.PARAMETERS, result.windowParameters)
        assertEquals(listOf(SIGNATURE), dataset.transactions.map { it.signature.value })
        assertEquals(1, dataset.observations.size)
        assertEquals(listOf(RATE), dataset.referenceRates)
        assertEquals(ReplayDataset.SCHEMA_V5, dataset.schemaVersion)
        assertEquals(listOf(MarketCoverage.of(window.pair, window.fromSlot, window.toSlot)), dataset.coverage)
        assertEquals(fixtureTrade().user.value, dataset.observations.single().trade?.trader)
    }

    @Test
    fun `a lead capture exports coverage from fifty slots before the buy with the buy's own trade by the tracked wallet, and the dataset loads`() {
        RecordedWalletFixtures.save(mapper, wallet())
        saveLeadMarketCapture()

        val export = service().export(request())
        val dataset = Files.newInputStream(output()).use { ReplayDatasetLoader(mapper).load(it) }

        val pair = window(SLOT - 50, SLOT + 4500).pair
        assertEquals(listOf(MarketCoverage.of(pair, Slot.of(SLOT - 50), Slot.of(SLOT + 4500))), dataset.coverage)
        assertEquals(listOf(SLOT, SLOT + 1), dataset.observations.map { it.slot.value })
        val own = dataset.observations.first()
        assertEquals(SIGNATURE, ObservationSignature.of(own))
        assertEquals(WALLET, own.trade?.trader)
        assertEquals(DatasetFingerprint.checksum(dataset), export.checksum)
    }

    @Test
    fun `a lead case export carries the lead window and parameters and the same data as the full export`() {
        RecordedWalletFixtures.save(mapper, wallet())
        saveLeadMarketCapture()
        service().export(request())

        val case = caseService().export(caseRequest(ReplayMarketFixtures.LEAD_PARAMETERS))

        val full = Files.newInputStream(output()).use { ReplayDatasetLoader(mapper).load(it) }
        val single = Files.newInputStream(case.output).use { ReplayDatasetLoader(mapper).load(it) }
        assertEquals(SLOT - 50, case.fromSlot)
        assertEquals(ReplayMarketFixtures.LEAD_PARAMETERS, case.windowParameters)
        assertEquals(full.observations, single.observations)
        assertEquals(full.coverage, single.coverage)
    }

    @Test
    fun `a lead export replays to the same scenario as the export of the same capture without its lead`() {
        RecordedWalletFixtures.save(mapper, wallet())
        saveLeadMarketCapture()
        service().export(request())
        val lead = Files.newInputStream(output()).use { ReplayDatasetLoader(mapper).load(it) }
        val legacy = lead.copy(observations = lead.observations.filter { it.slot.value > SLOT },
            coverage = lead.coverage.map { MarketCoverage.of(it.pair, Slot.of(SLOT + 1), it.throughSlot) })

        val config = SimulationConfig.mvp()
        val leadReport = ReplayRunner().run(lead, config)
        val legacyReport = ReplayRunner().run(legacy, config)

        assertEquals(legacyReport.scenarios.map { ScenarioEconomics.of(legacyReport, it) }, leadReport.scenarios.map { ScenarioEconomics.of(leadReport, it) })
    }

    @Test
    fun `a version two capture saved before the lead lists, inspects and exports its case through the console case path`() {
        RecordedWalletFixtures.save(mapper, wallet())
        saveRealMarketCapture()

        val (listed, export) = throughCasePath()

        assertEquals(SLOT + 1, listed.window.fromSlot.value)
        assertEquals(ReplayMarketFixtures.PARAMETERS, listed.windowParameters)
        assertEquals(ReplayMarketFixtures.PARAMETERS, export.windowParameters)
        assertEquals(listOf(MarketCoverage.of(listed.window.pair, Slot.of(SLOT + 1), Slot.of(SLOT + 4500))), exported(export).coverage)
    }

    @Test
    fun `a version three decided capture saved before the lead lists, inspects and exports its case through the console case path`() {
        RecordedWalletFixtures.save(mapper, wallet())
        saveRealMarketCapture(MarketWindowCutoff(SLOT + 1, "NO_DIP", "dip-outcome/v1", "a".repeat(64)))
        assertEquals(3, MarketHistoryArtifactStore(mapper).loadManifest(markets())?.schemaVersion)

        val (listed, export) = throughCasePath()

        assertEquals(SLOT + 1, listed.window.fromSlot.value)
        assertEquals(SLOT + 1, listed.observedThrough)
        assertEquals(ReplayMarketFixtures.PARAMETERS, export.windowParameters)
        assertEquals(listOf(MarketCoverage.of(listed.window.pair, Slot.of(SLOT + 1), Slot.of(SLOT + 1))), exported(export).coverage)
    }

    @Test
    fun `a lead capture lists, inspects and exports its case through the console case path`() {
        RecordedWalletFixtures.save(mapper, wallet())
        saveLeadMarketCapture()

        val (listed, export) = throughCasePath()

        assertEquals(SLOT - 50, listed.window.fromSlot.value)
        assertEquals(ReplayMarketFixtures.LEAD_PARAMETERS, listed.windowParameters)
        assertEquals(ReplayMarketFixtures.LEAD_PARAMETERS, export.windowParameters)
        assertEquals(listOf(MarketCoverage.of(listed.window.pair, Slot.of(SLOT - 50), Slot.of(SLOT + 4500))), exported(export).coverage)
    }

    /**
     * Lists the cases, inspects the single one and exports it from that inspection, as the console's case operations do. The
     * listing reads no artifact.
     */
    private fun throughCasePath(): Pair<CaptureCaseInspection, CaptureCaseExport> {
        val recorded = MarketHistoryCaptureConfiguration().recordedMarketHistory(mapper)
        var caseDecodes = 0
        val markets = object : RecordedMarketHistory by recorded {
            override fun inspect(directory: Path): MarketHistoryCaptureResult = error("The case path does not verify the whole capture")
            override fun decode(directory: Path): MarketHistoryDecodeResult = error("The case path does not decode the whole capture")
            override fun decodeCase(directory: Path, caseId: MarketCaseId) = recorded.decodeCase(directory, caseId).also { caseDecodes++ }
        }
        val inspections = CaptureCaseInspectionService(RecordedWalletPlanning(WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper)), markets)
        val listed = inspections.inspectCases(wallet(), markets()).cases.single()
        assertEquals(CaptureCaseStage.READY_TO_VALIDATE, listed.stage)
        assertEquals(0, caseDecodes)
        val inspected = inspections.inspectCase(wallet(), markets(), listed.caseId)
        assertEquals(CaptureCaseStage.READY, inspected.stage)
        val export = caseService(markets = markets).export(CaptureCaseExportRequest(wallet(), markets(), rates(), inspected.caseId,
            inspected.wallet, inspected.mint, inspected.anchorSignature, inspected.anchorSlot, inspected.instructionIndex,
            inspected.window.fromSlot.value, inspected.window.toSlot.value, inspected.windowParameters, directory.resolve("case.json")))
        return listed to export
    }

    private fun exported(export: CaptureCaseExport): ReplayDataset = Files.newInputStream(export.output).use { ReplayDatasetLoader(mapper).load(it) }

    @Test
    fun `a repeated case export is byte identical and leaves the existing file in place`() {
        RecordedWalletFixtures.save(mapper, wallet())
        saveRealMarketCapture()
        val first = caseService().export(caseRequest())
        val bytes = Files.readAllBytes(first.output)

        val second = caseService().export(caseRequest())

        assertEquals(first.checksum, second.checksum)
        assertFalse(second.written)
        assertTrue(bytes.contentEquals(Files.readAllBytes(first.output)))
    }

    @Test
    fun `a full and a case export of the same capture carry the same trade and coverage`() {
        RecordedWalletFixtures.save(mapper, wallet())
        saveRealMarketCapture()
        service().export(request())
        val case = caseService().export(caseRequest())

        val full = Files.newInputStream(output()).use { ReplayDatasetLoader(mapper).load(it) }
        val single = Files.newInputStream(case.output).use { ReplayDatasetLoader(mapper).load(it) }

        assertEquals(ReplayDataset.SCHEMA_V5, single.schemaVersion)
        assertEquals(full.observations.map { it.trade }, single.observations.map { it.trade })
        assertEquals(full.coverage, single.coverage)
    }

    @Test
    fun `an export of observations lacking their trade is refused instead of written as v3`() {
        RecordedWalletFixtures.save(mapper, wallet())
        saveRealMarketCapture()
        val recorded = MarketHistoryCaptureConfiguration().recordedMarketHistory(mapper)
        val untraded = recorded.decode(markets()).let { decoded -> decoded.copy(observations = decoded.observations.map { it.copy(trade = null) }) }

        assertCategory("TRADE_FIELDS_MISSING") { service(untraded).export(request()) }
        assertFalse(Files.exists(output()))
    }

    @Test
    fun `a case export of an observation lacking its trade is refused instead of written as v3`() {
        RecordedWalletFixtures.save(mapper, wallet())
        saveRealMarketCapture()
        val recorded = MarketHistoryCaptureConfiguration().recordedMarketHistory(mapper)
        val untraded = object : RecordedMarketHistory by recorded {
            override fun decodeCase(directory: Path, caseId: MarketCaseId) = recorded.decodeCase(directory, caseId)
                .let { decoded -> decoded.copy(observations = decoded.observations.map { it.copy(trade = null) }) }
        }

        assertCategory("TRADE_FIELDS_MISSING") { caseService(markets = untraded).export(caseRequest()) }
        assertFalse(Files.exists(caseRequest().output))
    }

    @Test
    fun `case export refuses a request whose mint differs from the selected case`() {
        RecordedWalletFixtures.save(mapper, wallet())
        saveRealMarketCapture()

        val request = caseRequest().copy(mint = MintAddress.of("11111111111111111111111111111112"))

        assertCategory("MARKET_CASE_IDENTITY_MISMATCH") { caseService().export(request) }
        assertFalse(Files.exists(request.output))
    }

    @Test
    fun `case export refuses two normalized wallet trades from the anchor transaction`() {
        RecordedWalletFixtures.save(mapper, wallet())
        saveRealMarketCapture()
        val delegate = WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper)
        val ambiguous = object : RecordedWalletHistory {
            override fun inspect(directory: Path) = delegate.inspect(directory)

            override fun decode(directory: Path, wallet: WalletAddress) = delegate.decode(directory, wallet).let { decoded ->
                decoded.copy(transactions = decoded.transactions + decoded.transactions.single().copy(instructionIndex = 99))
            }
        }

        assertCategory("CASE_ANCHOR_MULTITRADE_TRANSACTION") {
            caseService(ambiguous).export(caseRequest())
        }
        assertFalse(Files.exists(caseRequest().output))
    }

    @Test
    fun `a repeated export is byte identical and leaves the existing file in place`() {
        RecordedWalletFixtures.save(mapper, wallet())
        saveRealMarketCapture()
        val first = service().export(request())
        val bytes = Files.readAllBytes(output())

        val second = service().export(request())

        assertEquals(first.checksum, second.checksum)
        assertFalse(second.written)
        assertTrue(bytes.contentEquals(Files.readAllBytes(output())))
    }

    @Test
    fun `a version two wallet capture and an accumulator scope capture of the same buy export the same dataset`() {
        saveRealMarketCapture()
        val exports = listOf(
            "v3" to { target: Path -> RecordedWalletFixtures.save(mapper, target) },
            "v2" to { target: Path -> RecordedWalletFixtures.save(mapper, target, versionTwo = true) },
            "accumulator" to { target: Path -> RecordedWalletFixtures.save(mapper, target, scope = WalletHistoryScope.PUMPFUN_USER_VOLUME_ACCUMULATOR) },
        ).map { (name, save) ->
            val target = directory.resolve("wallet-$name")
            save(target)
            val decoded = WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper).decode(target, WalletAddress.of(WALLET))
            val export = service().export(RecordedReplayExportRequest(target, markets(), rates(), directory.resolve("replay-$name.json")))
            Triple(decoded.transactions, export.checksum, Files.readAllBytes(export.output))
        }
        assertEquals(2, WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper).inspect(directory.resolve("wallet-v2")).manifest.schemaVersion)
        assertEquals(1, exports.map { it.first }.distinct().size)
        assertEquals(1, exports.map { it.second }.distinct().size)
        assertTrue(exports.all { it.third.contentEquals(exports.first().third) })
    }

    @Test
    fun `refuses to replace a different existing file`() {
        RecordedWalletFixtures.save(mapper, wallet())
        saveRealMarketCapture()
        Files.writeString(output(), "previous dataset")

        assertCategory("OUTPUT_EXISTS") { service().export(request()) }

        assertEquals("previous dataset", Files.readString(output()))
    }

    @Test
    fun `refuses a market capture whose windows or parameters differ from the wallet capture's plan`() {
        RecordedWalletFixtures.save(mapper, wallet())
        val shifted = ReplayMarketFixtures.manifest(listOf(window(SLOT + 2, SLOT + 4500)))
        assertCategory("MARKET_CAPTURE_MISMATCH") { service(ReplayMarketFixtures.decoded(markets(), shifted, 1)).export(request()) }
        val planned = ReplayMarketFixtures.manifest(listOf(window(SLOT + 1, SLOT + 4500)))
        val otherParameters = ReplayMarketFixtures.decoded(markets(), planned, 1)
            .copy(windowParameters = ReplayMarketFixtures.PARAMETERS.copy(horizonSlots = 4499))
        assertCategory("MARKET_CAPTURE_MISMATCH") { service(otherParameters).export(request()) }
        assertFalse(Files.exists(output()))
    }

    @Test
    fun `refuses an incomplete market capture or a window that yields no observations`() {
        RecordedWalletFixtures.save(mapper, wallet())
        val planned = ReplayMarketFixtures.decoded(markets(), ReplayMarketFixtures.manifest(listOf(window(SLOT + 1, SLOT + 4500))), 1)
        assertCategory("MARKET_CAPTURE_INCOMPLETE") { service(planned.copy(complete = false)).export(request()) }
        val gap = planned.copy(windows = planned.windows.map { it.copy(detailGaps = 1, yieldsObservations = false) })
        assertCategory("MARKET_CAPTURE_INCOMPLETE") { service(gap).export(request()) }
        assertFalse(Files.exists(output()))
    }

    @Test
    fun `refuses in-window exclusions but not trades outside the window or quoted in the quote mint`() {
        RecordedWalletFixtures.save(mapper, wallet())
        val planned = ReplayMarketFixtures.decoded(markets(), ReplayMarketFixtures.manifest(listOf(window(SLOT + 1, SLOT + 4500))), 1)
        val blocking = MarketObservationExclusion.entries -
            setOf(MarketObservationExclusion.OUT_OF_WINDOW, MarketObservationExclusion.QUOTE_MINT)
        blocking.forEach { reason ->
            val excluded = planned.copy(exclusions = planned.exclusions + (reason to 1))
            assertCategory("MARKET_CAPTURE_EXCLUSIONS") { service(excluded).export(request()) }
        }
        assertFalse(Files.exists(output()))
        val outside = planned.copy(exclusions = planned.exclusions +
            mapOf(MarketObservationExclusion.OUT_OF_WINDOW to 4, MarketObservationExclusion.QUOTE_MINT to 1))
        assertEquals(1, service(outside).export(request()).observations)
    }

    @Test
    fun `refuses a recorded market directory that is unfinished or stopped at its page bound`() {
        RecordedWalletFixtures.save(mapper, wallet())
        val store = MarketHistoryArtifactStore(mapper)
        val planned = listOf(window(SLOT + 1, SLOT + 4500))
        store.save(markets(), ReplayMarketFixtures.manifest(planned, firstCoverage = MarketWindowCoverage.PENDING))
        assertCategory("CAPTURE_INCOMPLETE") { service().export(request()) }
        store.save(markets(), ReplayMarketFixtures.manifest(planned, firstCoverage = MarketWindowCoverage.COVERAGE_INCOMPLETE))
        assertCategory("MARKET_CAPTURE_INCOMPLETE") { service().export(request()) }
        assertFalse(Files.exists(output()))
    }

    @Test
    fun `refuses an incomplete wallet capture before reading the market capture`() {
        RecordedWalletFixtures.save(mapper, wallet(), complete = false)
        assertCategory("WALLET_CAPTURE_INCOMPLETE") { service(ReplayMarketFixtures.decoded(markets(),
            ReplayMarketFixtures.manifest(listOf(window(SLOT + 1, SLOT + 4500))), 1)).export(request()) }
        assertEquals(0, marketDecodes)
        assertFalse(Files.exists(output()))
    }

    @Test
    fun `refuses more observations than the replay format holds instead of truncating them`() {
        RecordedWalletFixtures.save(mapper, wallet())
        val tooMany = ReplayMarketFixtures.decoded(markets(), ReplayMarketFixtures.manifest(listOf(window(SLOT + 1, SLOT + 4500))), 100_001)
        assertCategory("DATASET_BOUNDS") { service(tooMany).export(request()) }
        assertFalse(Files.exists(output()))
    }

    /**
     * A capture saved before the lead: its window starts the slot after the buy and holds the one trade there. With [cutoff]
     * the window is decided, which makes it manifest version 3.
     */
    private fun saveRealMarketCapture(cutoff: MarketWindowCutoff? = null) =
        saveMarketCapture(window(SLOT + 1, SLOT + 4500), ReplayMarketFixtures.PARAMETERS, listOf(Triple(TRADE, SLOT + 1, TRADE_RESOURCE)), cutoff)

    /** A capture with the lead: its window starts 50 slots before the buy and holds the buy's own transaction and the next trade. */
    private fun saveLeadMarketCapture() = saveMarketCapture(window(SLOT - 50, SLOT + 4500), ReplayMarketFixtures.LEAD_PARAMETERS,
        listOf(Triple(TRADE, SLOT + 1, TRADE_RESOURCE), Triple(SIGNATURE, SLOT, BUY_RESOURCE)))

    /** [transactions] are (signature, slot, resource), newest first as discovery lists them. */
    private fun saveMarketCapture(
        window: MarketCaptureWindow,
        parameters: MarketCaptureWindowParameters,
        transactions: List<Triple<String, Long, String>>,
        cutoff: MarketWindowCutoff? = null,
    ) {
        val store = MarketHistoryArtifactStore(mapper)
        val records = transactions.map { (signature, slot, resource) ->
            val transaction = javaClass.getResourceAsStream(resource).use { mapper.readTree(requireNotNull(it)) }
            val artifact = store.writeTransaction(markets(), signature, transaction)
            MarketTransactionRecord(signature, slot, MarketTransactionOutcome.CAPTURED, artifact.path, artifact.sha256, artifact.bytes)
        }
        val manifest = ReplayMarketFixtures.manifest(listOf(window), records, parameters = parameters)
        store.save(markets(), if (cutoff == null) manifest else manifest.copy(schemaVersion = 3, windows = manifest.windows.map { it.copy(cutoff = cutoff) }))
    }

    @Test
    fun `refuses a market capture whose trades have a minute without a recorded rate`() {
        RecordedWalletFixtures.save(mapper, wallet())
        saveRealMarketCapture()
        assertCategory("REFERENCE_RATES_INCOMPLETE") { service(rates = emptyList()).export(request()) }
        assertFalse(Files.exists(output()))
    }

    private fun service(market: MarketHistoryDecodeResult? = null, rates: List<ReferenceRate> = listOf(RATE)): RecordedReplayExportService {
        val recorded = MarketHistoryCaptureConfiguration().recordedMarketHistory(mapper)
        val markets = object : RecordedMarketHistory {
            override fun inspect(directory: Path): MarketHistoryCaptureResult = recorded.inspect(directory)

            override fun decode(directory: Path): MarketHistoryDecodeResult {
                marketDecodes++
                return market ?: recorded.decode(directory)
            }
        }
        val recordedRates = object : RecordedReferenceRates {
            override fun inspect(directory: Path): ReferenceRateCaptureResult = error("Export reads rates, not their audit")

            override fun rates(directory: Path): List<ReferenceRate> = rates
        }
        return RecordedReplayExportService(WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper), markets, recordedRates, mapper)
    }

    private fun caseService(
        walletHistory: RecordedWalletHistory = WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper),
        markets: RecordedMarketHistory = MarketHistoryCaptureConfiguration().recordedMarketHistory(mapper),
    ): RecordedCaseReplayExportService {
        val recordedRates = object : RecordedReferenceRates {
            override fun inspect(directory: Path): ReferenceRateCaptureResult = error("Export reads rates, not their audit")
            override fun rates(directory: Path): List<ReferenceRate> = listOf(RATE)
        }
        return RecordedCaseReplayExportService(
            walletHistory,
            markets,
            recordedRates,
            mapper,
        )
    }

    private fun caseRequest(parameters: MarketCaptureWindowParameters = ReplayMarketFixtures.PARAMETERS): CaptureCaseExportRequest {
        val window = window(SLOT - parameters.leadSlots, SLOT + 4500)
        val planning = com.trenching.bot.replay.application.RecordedWalletPlanning(
            WalletHistoryCaptureConfiguration().recordedWalletHistory(mapper),
        )
        val anchor = planning.plan(wallet(), parameters.leadSlots).let { planning.anchor(it, window) }
        return CaptureCaseExportRequest(
            wallet(), markets(), rates(), MarketCaseId.from(window), WalletAddress.of(WALLET), MintAddress.of(MINT),
            anchor.signature, anchor.slot.value, anchor.instructionIndex, window.fromSlot.value, window.toSlot.value,
            parameters, directory.resolve("case.json"),
        )
    }

    /** The `TradeEvent` of the captured Pump.fun transaction, as the protocol reader decodes it. */
    private fun fixtureTrade(): PumpFunInstruction.TradeEvent {
        val root = javaClass.getResourceAsStream("/marketdata/pumpfun-trade-453004997.json").use { mapper.readTree(requireNotNull(it)) }
        return PumpFunInstructionReader.read(if (root.has("result")) root.path("result") else root)
            .filterIsInstance<PumpFunInstruction.TradeEvent>().single()
    }

    private fun request() = RecordedReplayExportRequest(wallet(), markets(), rates(), output())

    private fun rates(): Path = directory.resolve("rates")

    private fun window(from: Long, to: Long) =
        MarketCaptureWindow.of(MintAddress.of(MINT), Slot.of(from), Slot.of(to), TransactionSignature.of(SIGNATURE))

    private fun wallet(): Path = directory.resolve("wallet")

    private fun markets(): Path = directory.resolve("markets")

    private fun output(): Path = directory.resolve("replay.json")

    private fun assertCategory(category: String, action: () -> Unit) {
        val failure = assertFailsWith<CaptureValidationException> { action() }
        assertEquals(category, failure.category)
    }

    private companion object {
        /** A synthetic test rate for the minute of the captured trade; never a recorded Binance value. */
        val RATE = ReferenceRate(Instant.parse("2026-10-03T17:47:00Z"), BigDecimal("150"), "test-fixture:SOLUSDT:1m:open")
        const val TRADE = "2YHkUxxjxax3y2f8efvMgzboRQ6XDMQpqck7pNpBbEawhFhgAzB32Lh7eRX7RhfkvgjARf2kHgQJWz7rZPDnhvxM"
        const val TRADE_RESOURCE = "/marketdata/pumpfun-trade-453004997.json"

        /** Decu's real buy of the wallet capture, which a lead window also records as a market trade in its own slot. */
        const val BUY_RESOURCE = "/walletactivity/decoder/pumpfun-buy.json"
    }
}
