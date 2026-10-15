package com.trenching.bot.replay

import com.trenching.bot.replay.api.CommandArguments
import com.trenching.bot.replay.api.TransactionCacheOption
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class TransactionCacheOptionTest {
    private fun split(vararg args: String) = TransactionCacheOption.split(arrayOf(*args))

    @Test
    fun `without the option the arguments and the absent cache are returned unchanged`() {
        val result = split("--wallet", "w", "--directory", "d") as CommandArguments.Valid
        assertEquals(null, result.value.directory)
        assertEquals(listOf("--wallet", "w", "--directory", "d"), result.value.remaining.toList())
    }

    @Test
    fun `the option is removed from the arguments the capture command parses`() {
        val result = split("--wallet", "w", "--transaction-cache-directory", "/var/cache/tx", "--directory", "d") as CommandArguments.Valid
        assertEquals(Path.of("/var/cache/tx"), result.value.directory)
        assertEquals(listOf("--wallet", "w", "--directory", "d"), result.value.remaining.toList())
    }

    @Test
    fun `a repeated, blank or option like value is invalid`() {
        listOf(
            arrayOf("--transaction-cache-directory", "/a", "--transaction-cache-directory", "/b"),
            arrayOf("--transaction-cache-directory", " "),
            arrayOf("--transaction-cache-directory", "--directory"),
        ).forEach { assertTrue(TransactionCacheOption.split(it) == CommandArguments.Invalid, it.toList().toString()) }
    }

    @Test
    fun `an odd argument list is left for the command to reject`() {
        val result = split("--transaction-cache-directory") as CommandArguments.Valid
        assertEquals(null, result.value.directory)
        assertEquals(listOf("--transaction-cache-directory"), result.value.remaining.toList())
    }
}
