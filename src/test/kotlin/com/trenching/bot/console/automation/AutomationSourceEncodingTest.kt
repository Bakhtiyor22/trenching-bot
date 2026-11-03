package com.trenching.bot.console.automation

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/** The automation sources are UTF-8; a byte from another encoding, such as a Windows-1252 dash, shows as a broken character in IDEs and diffs. */
class AutomationSourceEncodingTest {
    @Test
    fun `every automation source file decodes as UTF-8`() {
        val broken = ROOTS.flatMap { root ->
            Files.walk(root).use { paths -> paths.filter { it.extension == "kt" }.filter { !decodes(it) }.map { it.toString() }.toList() }
        }

        assertEquals(emptyList(), broken)
    }

    private fun decodes(file: Path): Boolean {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(Files.readAllBytes(file)))
            true
        } catch (_: CharacterCodingException) {
            false
        }
    }

    private companion object {
        val ROOTS: List<Path> = listOf(
            Path.of("src/main/kotlin/com/trenching/bot/console/automation"),
            Path.of("src/test/kotlin/com/trenching/bot/console/automation"),
        )
    }
}
