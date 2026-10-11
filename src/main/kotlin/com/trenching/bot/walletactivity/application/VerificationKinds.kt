package com.trenching.bot.walletactivity.application

import com.trenching.bot.walletactivity.domain.CreationCandidateKind
import com.trenching.bot.walletactivity.domain.VerificationEntryKind

/**
 * How each artifact was classified for verification, by signature: verification artifacts as accumulator entries and as
 * creation candidates, and wallet-signed token-account artifacts as creation candidates, so B can be its own creation.
 */
internal data class VerificationKinds(
    val entries: Map<String, VerificationEntryKind> = emptyMap(),
    val candidates: Map<String, CreationCandidateKind> = emptyMap(),
    val tokenAccountCandidates: Map<String, CreationCandidateKind> = emptyMap(),
)
