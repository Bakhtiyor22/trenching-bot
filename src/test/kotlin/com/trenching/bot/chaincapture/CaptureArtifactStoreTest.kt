package com.trenching.bot.chaincapture

import com.trenching.bot.support.DirectoryLinks
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Every link check also refuses a Windows directory junction, which is a reparse point rather than a symbolic link. */
class CaptureArtifactStoreTest {
    @TempDir lateinit var directory: Path
    private val store = CaptureArtifactStore(testMapper)
    private val signature = "5".repeat(88)
    private val transaction = exactJson(validTransactionJson(signature))

    @Test
    fun `a transactions directory that is a link is refused and nothing is written through it`() {
        val capture = Files.createDirectory(directory.resolve("capture"))
        val outside = Files.createDirectory(directory.resolve("outside"))
        DirectoryLinks.create(capture.resolve(CaptureArtifactStore.TRANSACTIONS_DIRECTORY), outside)

        val failure = assertFailsWith<CaptureValidationException> { store.writeTransaction(capture, signature, transaction) }

        assertEquals("UNSAFE_PATH", failure.category)
        assertTrue(Files.list(outside).use { it.toList() }.isEmpty())
    }

    @Test
    fun `an artifact reached through a linked directory is never read`() {
        val written = store.writeTransaction(directory.resolve("real"), signature, transaction)
        val capture = Files.createDirectory(directory.resolve("capture"))
        DirectoryLinks.create(capture.resolve(CaptureArtifactStore.TRANSACTIONS_DIRECTORY), directory.resolve("real/${CaptureArtifactStore.TRANSACTIONS_DIRECTORY}"))

        val failure = assertFailsWith<CaptureValidationException> { store.readArtifact(capture, written.path, written.bytes, written.sha256) }

        assertEquals("SYMLINK", failure.category)
    }
}
