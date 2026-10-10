package com.trenching.bot.console.capture

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Path

class CapturePathPolicyTest {
    @TempDir lateinit var root: Path
    @Test fun `rejects traversal absolute aliases sensitive files and overlapping directories`() {
        val policy = CapturePathPolicy(root)
        listOf("../escape", "/absolute", "C:/absolute", "wallet/../market", "wallet/.env", "wallet/test.secret", "wallet\\nested").forEach { assertThrows(CaptureRequestException::class.java) { policy.resolve(it) } }
        assertThrows(CaptureRequestException::class.java) { policy.independent(listOf("wallet", "wallet/child")) }
        assertEquals(root.resolve("wallet"), policy.resolve("wallet"))
    }
}
