package com.trenching.bot.walletactivity

import com.trenching.bot.walletactivity.infrastructure.capture.WalletTransactionEvidence
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

class WalletTransactionEvidenceTest {
    private val mapper = JsonMapper.builder().build()
    private val wallet = "1".repeat(32)
    private val counterparty = "1".repeat(31) + "2"
    private val program = "1".repeat(31) + "3"
    private val mint = "1".repeat(31) + "4"
    private val signature = "1".repeat(64)
    private val selection = WalletHistorySelection(signature, 100)
    private val evidence = WalletTransactionEvidence()

    @Test
    fun `classifies a minimal valid jsonParsed transaction by signer status`() {
        assertEquals(WalletHistoryOutcome.UNCLASSIFIED, classify { })
        assertEquals(WalletHistoryOutcome.NON_SIGNER, classify { (accountKeys(it)[0] as ObjectNode).put("signer", false) })
        assertEquals(WalletHistoryOutcome.UNCLASSIFIED, classify { it.put("version", "legacy") })
        assertEquals(WalletHistoryOutcome.UNAVAILABLE, evidence.classify(mapper.readTree("null"), selection, wallet))
    }

    @Test
    fun `treats every transaction error as contradicting the successful discovery listing`() {
        assertEquals(WalletHistoryOutcome.INVALID_TRANSACTION, classify { meta(it).put("err", "AccountInUse") })
        assertEquals(WalletHistoryOutcome.INVALID_TRANSACTION, classify { meta(it).set("err", mapper.readTree("""{"InstructionError":[0,"Custom"]}""")) })
        listOf("5", "[]", "true", "1.5").forEach { invalid ->
            assertEquals(WalletHistoryOutcome.INVALID_TRANSACTION, classify { meta(it).set("err", mapper.readTree(invalid)) }, invalid)
        }
        assertEquals(WalletHistoryOutcome.INVALID_TRANSACTION, classify { meta(it).remove("err") })
    }

    @Test
    fun `classifies version one like version zero and every integral version above one as unsupported`() {
        assertEquals(WalletHistoryOutcome.UNCLASSIFIED, classify { it.put("version", 1) })
        assertEquals(WalletHistoryOutcome.INVALID_TRANSACTION, classify { it.put("version", 1); meta(it).put("err", "AccountInUse") })
        listOf("2", "99999999999999999999999999").forEach { version ->
            assertEquals(WalletHistoryOutcome.UNSUPPORTED_VERSION, classify { it.set("version", mapper.readTree(version)) }, version)
        }
        listOf("-1", "1.5", "\"v0\"", "{}", "null").forEach { version ->
            assertEquals(WalletHistoryOutcome.INVALID_TRANSACTION, classify { it.set("version", mapper.readTree(version)) }, version)
        }
    }

    @Test
    fun `withholds public on-chain text carrying links or credential keywords without storing it`() {
        assertEquals(WalletHistoryOutcome.WITHHELD_UNSAFE_TEXT, classify { meta(it).putArray("logMessages").add("Program log: Memo visit https://spam.invalid") })
        assertEquals(WalletHistoryOutcome.WITHHELD_UNSAFE_TEXT, classify {
            (instructions(it)[0] as ObjectNode).putObject("parsed").put("memo", "Bearer giveaway")
        })
        assertEquals(WalletHistoryOutcome.WITHHELD_UNSAFE_TEXT, classify {
            (instructions(it)[0] as ObjectNode).putObject("parsed").putObject("info").put("authorization", "x")
        })
    }

    @Test
    fun `reports identity and structure failures before withholding unsafe text`() {
        assertEquals(WalletHistoryOutcome.INVALID_TRANSACTION, classify {
            it.put("slot", 101)
            meta(it).putArray("logMessages").add("https://spam.invalid")
        })
        assertEquals(WalletHistoryOutcome.INVALID_TRANSACTION, classify {
            message(it).put("instructions", "invalid")
            meta(it).putArray("logMessages").add("https://spam.invalid")
        })
        assertEquals(WalletHistoryOutcome.INVALID_TRANSACTION, classify {
            meta(it).put("err", "AccountInUse").putArray("logMessages").add("see https://spam.invalid")
        })
    }

    @Test
    fun `rejects malformed captured structure as invalid rather than advertising complete acquisition`() {
        val hostile: Map<String, (ObjectNode) -> Unit> = mapOf(
            "instructions is a string" to { message(it).put("instructions", "invalid") },
            "instruction without programId" to { (instructions(it)[0] as ObjectNode).remove("programId") },
            "instruction with invalid programId" to { (instructions(it)[0] as ObjectNode).put("programId", "not-an-address") },
            "instruction is a string" to { instructions(it).set(0, mapper.readTree("\"x\"")) },
            "blockTime is fractional" to { it.put("blockTime", 1.5) },
            "blockTime is text" to { it.put("blockTime", "yesterday") },
            "blockTime is huge" to { it.set("blockTime", mapper.readTree("99999999999999999999999")) },
            "blockTime is missing" to { it.remove("blockTime") },
            "slot is huge" to { it.set("slot", mapper.readTree("99999999999999999999999")) },
            "slot is text" to { it.put("slot", "100") },
            "slot is negative" to { it.put("slot", -1) },
            "transaction is a string" to { it.put("transaction", "x") },
            "meta is null" to { it.putNull("meta") },
            "message is an array" to { (it.get("transaction") as ObjectNode).putArray("message") },
            "signatures are empty" to { (it.get("transaction") as ObjectNode).putArray("signatures") },
            "signature is a number" to { (it.get("transaction") as ObjectNode).putArray("signatures").add(5) },
            "second signature is malformed" to { (it.get("transaction") as ObjectNode).putArray("signatures").add(signature).add("bad") },
            "accountKeys are empty" to { message(it).putArray("accountKeys") },
            "accountKey is a string" to { message(it).putArray("accountKeys").add(wallet) },
            "pubkey is a number" to { (accountKeys(it)[0] as ObjectNode).put("pubkey", 5) },
            "signer is text" to { (accountKeys(it)[0] as ObjectNode).put("signer", "true") },
            "writable is text" to { (accountKeys(it)[0] as ObjectNode).put("writable", "yes") },
            "source is a number" to { (accountKeys(it)[0] as ObjectNode).put("source", 1) },
            "too many accountKeys" to { keys -> val array = accountKeys(keys); repeat(256) { array.addObject().put("pubkey", counterparty).put("signer", false) } },
            "recentBlockhash is missing" to { message(it).remove("recentBlockhash") },
            "recentBlockhash is a number" to { message(it).put("recentBlockhash", 1) },
            "addressTableLookups is an object" to { message(it).putObject("addressTableLookups") },
            "addressTableLookup is a string" to { message(it).putArray("addressTableLookups").add("x") },
            "fee is negative" to { meta(it).put("fee", -1) },
            "fee is fractional" to { meta(it).put("fee", 1.5) },
            "fee is missing" to { meta(it).remove("fee") },
            "preBalances are missing" to { meta(it).remove("preBalances") },
            "postBalance is negative" to { meta(it).putArray("postBalances").add(-1).add(1) },
            "postBalance is text" to { meta(it).putArray("postBalances").add("1").add(1) },
            "balances differ in length" to { meta(it).putArray("postBalances").add(1) },
            "status is a string" to { meta(it).put("status", "Ok") },
            "innerInstructions is an object" to { meta(it).putObject("innerInstructions") },
            "inner index is text" to { innerInstruction(it).put("index", "0") },
            "inner instructions missing" to { innerInstruction(it).remove("instructions") },
            "inner instruction programId invalid" to {
                ((innerInstruction(it).get("instructions") as ArrayNode)[0] as ObjectNode).put("programId", 7)
            },
            "logMessages contain a number" to { meta(it).putArray("logMessages").add(1) },
            "logMessages is a string" to { meta(it).put("logMessages", "log") },
            "token balance is a string" to { meta(it).putArray("preTokenBalances").add("x") },
            "token accountIndex out of range" to { tokenBalance(it).put("accountIndex", 256) },
            "token accountIndex text" to { tokenBalance(it).put("accountIndex", "1") },
            "token mint invalid" to { tokenBalance(it).put("mint", "bad") },
            "token owner number" to { tokenBalance(it).put("owner", 1) },
            "token programId number" to { tokenBalance(it).put("programId", 1) },
            "token amount numeric" to { tokenAmount(it).put("amount", 1000) },
            "token amount exponent" to { tokenAmount(it).put("amount", "1e3") },
            "token amount negative" to { tokenAmount(it).put("amount", "-1") },
            "token amount above u64" to { tokenAmount(it).put("amount", "18446744073709551616") },
            "token amount empty" to { tokenAmount(it).put("amount", "") },
            "token decimals out of range" to { tokenAmount(it).put("decimals", 256) },
            "token decimals missing" to { tokenAmount(it).remove("decimals") },
            "uiTokenAmount missing" to { tokenBalance(it).remove("uiTokenAmount") },
            "rewards is an object" to { meta(it).putObject("rewards") },
            "loadedAddresses is an array" to { meta(it).putArray("loadedAddresses") },
            "loadedAddresses holds numbers" to { meta(it).putObject("loadedAddresses").putArray("writable").add(1) },
            "returnData is a string" to { meta(it).put("returnData", "x") },
            "computeUnitsConsumed negative" to { meta(it).put("computeUnitsConsumed", -1) },
            "costUnits fractional" to { meta(it).put("costUnits", 1.5) },
            "unexpected result field" to { it.put("extra", 1) },
            "transactionIndex is text" to { it.put("transactionIndex", "1127") },
            "transactionIndex is negative" to { it.put("transactionIndex", -1) },
            "transactionIndex is fractional" to { it.put("transactionIndex", 1.5) },
            "transactionConfig is a string" to { message(it).put("transactionConfig", "x") },
            "transactionConfig is an array" to { message(it).putArray("transactionConfig") },
            "transactionConfig priorityFee is text" to { message(it).putObject("transactionConfig").put("priorityFee", "1624") },
            "transactionConfig computeUnitLimit is negative" to { message(it).putObject("transactionConfig").put("computeUnitLimit", -1) },
            "transactionConfig has an unexpected field" to { message(it).putObject("transactionConfig").put("extra", 1) },
        )
        hostile.forEach { (name, mutation) ->
            assertEquals(WalletHistoryOutcome.INVALID_TRANSACTION, classify(mutation), name)
        }
    }

    @Test
    fun `accepts explicitly null optional fields and the largest u64 token amount`() {
        val accepted: Map<String, (ObjectNode) -> Unit> = mapOf(
            "null blockTime" to { it.putNull("blockTime") },
            "null logMessages" to { meta(it).putNull("logMessages") },
            "null innerInstructions" to { meta(it).putNull("innerInstructions") },
            "absent optional meta" to { meta(it).retain("err", "fee", "preBalances", "postBalances") },
            "absent lookups" to { message(it).remove("addressTableLookups") },
            "max u64 amount" to { tokenAmount(it).put("amount", "18446744073709551615") },
        )
        accepted.forEach { (name, mutation) -> assertEquals(WalletHistoryOutcome.UNCLASSIFIED, classify(mutation), name) }
    }

    @Test
    fun `accepts the transaction index and version one transaction config observed in captured mainnet data`() {
        val observedV1: (ObjectNode) -> Unit = {
            it.put("version", 1)
            it.put("transactionIndex", 1127)
            message(it).remove("addressTableLookups")
            message(it).set("transactionConfig", mapper.readTree(
                """{"priorityFee":1624,"computeUnitLimit":79981,"loadedAccountsDataSizeLimit":13631488,"heapSize":null}""",
            ))
        }
        assertEquals(WalletHistoryOutcome.UNCLASSIFIED, classify(observedV1))
        assertEquals(WalletHistoryOutcome.INVALID_TRANSACTION, classify { observedV1(it); meta(it).set("err", mapper.readTree("""{"InstructionError":[0,{"Custom":7}]}""")) })
        assertEquals(WalletHistoryOutcome.UNCLASSIFIED, classify { observedV1(it); message(it).putObject("transactionConfig") })
    }

    @Test
    fun `classifies hostile root shapes without throwing`() {
        listOf("[]", "\"x\"", "5", "{}", """{"slot":{}}""", """{"slot":100,"transaction":{"signatures":[{}]}}""",
            """{"slot":100,"version":0,"transaction":{"signatures":["$signature"],"message":{"accountKeys":[{"pubkey":{},"signer":true}]}}}""",
        ).forEach { json ->
            assertEquals(WalletHistoryOutcome.INVALID_TRANSACTION, evidence.classify(mapper.readTree(json), selection, wallet), json)
        }
    }

    private fun classify(mutation: (ObjectNode) -> Unit): WalletHistoryOutcome {
        val node = valid()
        mutation(node)
        return evidence.classify(node, selection, wallet)
    }

    private fun accountKeys(node: ObjectNode): ArrayNode = message(node).get("accountKeys") as ArrayNode
    private fun instructions(node: ObjectNode): ArrayNode = message(node).get("instructions") as ArrayNode
    private fun message(node: ObjectNode): ObjectNode = (node.get("transaction") as ObjectNode).get("message") as ObjectNode
    private fun meta(node: ObjectNode): ObjectNode = node.get("meta") as ObjectNode
    private fun innerInstruction(node: ObjectNode): ObjectNode = meta(node).get("innerInstructions")[0] as ObjectNode
    private fun tokenBalance(node: ObjectNode): ObjectNode = meta(node).get("preTokenBalances")[0] as ObjectNode
    private fun tokenAmount(node: ObjectNode): ObjectNode = tokenBalance(node).get("uiTokenAmount") as ObjectNode

    private fun valid(): ObjectNode = mapper.readTree("""
        {"slot":100,"blockTime":1790985600,"version":0,
         "meta":{"err":null,"status":{"Ok":null},"fee":5000,"preBalances":[1000000,1],"postBalances":[995000,1],
           "innerInstructions":[{"index":0,"instructions":[{"programId":"$program","program":"system","parsed":{"type":"transfer"},"stackHeight":2}]}],
           "logMessages":["Program $program invoke [1]","Program $program success"],
           "preTokenBalances":[{"accountIndex":1,"mint":"$mint","owner":"$wallet","programId":"$program","uiTokenAmount":{"amount":"1000","decimals":6,"uiAmountString":"0.001"}}],
           "postTokenBalances":[],"rewards":[],"loadedAddresses":{"writable":[],"readonly":["$counterparty"]},
           "returnData":null,"computeUnitsConsumed":150,"costUnits":1200},
         "transaction":{"signatures":["$signature"],"message":{
           "accountKeys":[{"pubkey":"$wallet","signer":true,"writable":true,"source":"transaction"},{"pubkey":"$counterparty","signer":false,"writable":false,"source":"lookupTable"}],
           "instructions":[{"programId":"$program","accounts":["$wallet"],"data":"3Bxs","stackHeight":null}],
           "recentBlockhash":"$program","addressTableLookups":[{"accountKey":"$counterparty","writableIndexes":[],"readonlyIndexes":[0]}]}}}
    """.trimIndent()) as ObjectNode
}
