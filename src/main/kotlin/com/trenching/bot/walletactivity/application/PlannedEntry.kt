package com.trenching.bot.walletactivity.application

import com.trenching.bot.walletactivity.VerificationRecord

/** A verification entry to resolve, with the artifact it can reuse instead of a fetch, if any. */
internal data class PlannedEntry(val record: VerificationRecord, val artifact: StoredArtifact?)
