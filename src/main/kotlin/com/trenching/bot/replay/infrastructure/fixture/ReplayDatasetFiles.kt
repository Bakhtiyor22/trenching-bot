package com.trenching.bot.replay.infrastructure.fixture

import com.trenching.bot.chaincapture.CaptureValidationException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path

/** Writes an exported dataset file without ever replacing a different one. */
object ReplayDatasetFiles {
    /**
     * Writes [content] to a temporary file and renames it to [target], returning true, or returns false when [target]
     * already holds exactly [content]. A different existing file, including one created while this runs, is refused with
     * `OUTPUT_EXISTS` and left untouched: the rename never replaces an existing target.
     */
    fun writeOnce(target: Path, content: String): Boolean {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (Files.exists(target)) {
            if (Files.isRegularFile(target) && Files.readAllBytes(target).contentEquals(bytes)) return false
            throw CaptureValidationException("OUTPUT_EXISTS")
        }
        val parent = target.toAbsolutePath().normalize().parent
        Files.createDirectories(parent)
        val pending = Files.createTempFile(parent, ".replay-", ".pending")
        try {
            Files.write(pending, bytes)
            Files.move(pending, target)
        } catch (_: FileAlreadyExistsException) {
            throw CaptureValidationException("OUTPUT_EXISTS")
        } finally { Files.deleteIfExists(pending) }
        return true
    }
}
