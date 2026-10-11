package com.trenching.bot.walletactivity.application

/** An artifact a capture manifest already references, with the size and hash it was recorded with. */
internal data class StoredArtifact(val path: String, val sha256: String, val bytes: Long) {
    companion object {
        /** Null for a record without an artifact. */
        fun of(path: String?, sha256: String?, bytes: Long): StoredArtifact? =
            if (path != null && sha256 != null) StoredArtifact(path, sha256, bytes) else null
    }
}
