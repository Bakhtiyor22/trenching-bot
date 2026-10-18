package com.trenching.bot.console.capture

import java.nio.file.Files
import java.time.Instant

/** The ways a session directory can fail to read as a capture: each entry breaks a fresh session named by its key. */
internal object UnreadableSessionFiles {
    fun variants(store: CaptureFileStore): Map<String, (String) -> Unit> = mapOf(
        "a directory without session.json" to { id -> Files.createDirectories(store.paths.resolve("console/$id")) },
        "an unknown metadata version" to { id -> write(store, id) { it.replace("\"version\":1", "\"version\":2") } },
        "a JSON parse error" to { id -> write(store, id) { "{" } },
    )

    private fun write(store: CaptureFileStore, id: String, content: (String) -> String) {
        store.save(CaptureSessionRecord(id = id, requestId = id, fingerprint = id, name = id, createdAt = Instant.parse("2026-10-01T00:00:00Z"),
            wallet = null, walletAttemptId = "w-$id", marketAttemptId = null, ratesAttemptId = null), create = true)
        val file = store.paths.resolve("console/$id/session.json")
        Files.writeString(file, content(Files.readString(file)))
    }
}
