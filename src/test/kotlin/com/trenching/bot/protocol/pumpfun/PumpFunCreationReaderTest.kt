package com.trenching.bot.protocol.pumpfun

import com.trenching.bot.common.Base58
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

class PumpFunCreationReaderTest {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `reads the mint of a top level create and an inner create v2`() {
        val transaction = transaction(
            topLevel = listOf(instruction(PumpFunLayout.PROGRAM_ID, PumpFunLayout.CREATE_DISCRIMINATOR, MINT)),
            inner = listOf(instruction(PumpFunLayout.PROGRAM_ID, PumpFunLayout.CREATE_V2_DISCRIMINATOR, OTHER_MINT)),
        )
        assertEquals(listOf(MINT, OTHER_MINT), PumpFunCreationReader.createdMints(transaction))
    }

    @Test
    fun `ignores another program's instruction, a buy and unreadable data`() {
        val transaction = transaction(topLevel = listOf(
            instruction(OTHER_PROGRAM, PumpFunLayout.CREATE_DISCRIMINATOR, MINT),
            instruction(PumpFunLayout.PROGRAM_ID, PumpFunLayout.BUY_DISCRIMINATOR, MINT),
            mapper.createObjectNode().put("programId", PumpFunLayout.PROGRAM_ID).put("data", "0OIl").also { it.putArray("accounts").add(MINT) },
        ))
        assertEquals(emptyList(), PumpFunCreationReader.createdMints(transaction))
    }

    private fun instruction(programId: String, discriminator: ByteArray, mint: String): ObjectNode = mapper.createObjectNode().apply {
        put("programId", programId)
        putArray("accounts").add(mint).add(OTHER_PROGRAM)
        put("data", Base58.encode(discriminator + ByteArray(16)))
    }

    private fun transaction(topLevel: List<ObjectNode>, inner: List<ObjectNode> = emptyList()): ObjectNode = mapper.createObjectNode().apply {
        putObject("transaction").putObject("message").putArray("instructions").addAll(topLevel)
        putObject("meta").putArray("innerInstructions").addObject().put("index", 0).putArray("instructions").addAll(inner)
    }

    private companion object {
        const val MINT = "5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs"
        const val OTHER_MINT = "hoSDHBQR236CoSLptRpDKPSU1JbcG2Ry52X1iq6pump"
        const val OTHER_PROGRAM = "FLASHX8DrLbgeR8FcfNV1F5krxYcYMUdBkrP1EPBtxB9"
    }
}
