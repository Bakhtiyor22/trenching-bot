package com.trenching.bot.chaincapture

import com.trenching.bot.protocol.solana.SolanaPrograms
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class UnsafeTextTest {
    @Test
    fun `links and credential keywords in text or keys are unsafe`() {
        assertTrue(UnsafeText.foundIn(testMapper.readTree("""{"meta":{"logMessages":["Program log: see https://example.com"]}}""")))
        assertTrue(UnsafeText.foundIn(testMapper.readTree("""{"memo":"Bearer abc"}""")))
        assertTrue(UnsafeText.foundIn(testMapper.readTree("""{"rpcUrl":"x"}""")))
        assertFalse(UnsafeText.foundIn(testMapper.readTree("""{"meta":{"logMessages":["Program log: Instruction: Buy"]}}""")))
    }

    @Test
    fun `the metadata uri of a Token-2022 token metadata initialization is not withheld for being a link`() {
        val creation = transaction(tokenMetadata(SolanaPrograms.TOKEN_2022, "initializeTokenMetadata", "https://metadata.example/a.json"))
        assertFalse(UnsafeText.foundIn(creation))
        assertTrue(creation.toString().contains("https://metadata.example/a.json"), "the check must not change the transaction")
    }

    @Test
    fun `the exemption covers only that uri`() {
        listOf(
            "credential in the uri" to tokenMetadata(SolanaPrograms.TOKEN_2022, "initializeTokenMetadata", "https://x.example/?api_key=1"),
            "link in the name" to tokenMetadata(SolanaPrograms.TOKEN_2022, "initializeTokenMetadata", "ok", name = "https://x.example"),
            "another instruction" to tokenMetadata(SolanaPrograms.TOKEN_2022, "updateTokenMetadataField", "https://x.example"),
            "another program" to tokenMetadata(SolanaPrograms.TOKEN, "initializeTokenMetadata", "https://x.example"),
        ).forEach { (case, instruction) -> assertTrue(UnsafeText.foundIn(transaction(instruction)), case) }
        val linkElsewhere = testMapper.readTree("""{"meta":{"innerInstructions":[{"index":0,"instructions":[
            ${tokenMetadata(SolanaPrograms.TOKEN_2022, "initializeTokenMetadata", "https://metadata.example/a.json")}]}],
            "logMessages":["Program log: https://x.example"]}}""")
        assertTrue(UnsafeText.foundIn(linkElsewhere))
    }

    private fun transaction(instruction: String) =
        testMapper.readTree("""{"meta":{"innerInstructions":[{"index":0,"instructions":[$instruction]}]}}""")

    private fun tokenMetadata(programId: String, type: String, uri: String, name: String = "Gigapump") = """
        {"program":"spl-token","programId":"$programId","parsed":{"type":"$type","info":{
            "mint":"DGYwDZGKQNDkL49b6UtVFJEUmnB5PXfw4BUkCd7Npump","name":"$name","symbol":"GIGA","uri":"$uri"}}}
    """
}
