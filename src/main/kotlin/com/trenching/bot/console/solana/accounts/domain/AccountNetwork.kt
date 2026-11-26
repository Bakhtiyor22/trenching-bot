package com.trenching.bot.console.solana.accounts.domain

/** What this process knows of the network an account's endpoint serves. The primary counts as [MAINNET] as configured; the backup is [UNVERIFIED] until its genesis hash was read. */
enum class AccountNetwork { UNVERIFIED, MAINNET, WRONG_NETWORK }
