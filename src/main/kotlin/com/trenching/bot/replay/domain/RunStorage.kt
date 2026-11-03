package com.trenching.bot.replay.domain

/**
 * Where a stored run's inputs live. [COPY] keeps the dataset snapshot and its market observations under the run;
 * [DATASET_REFERENCE] keeps neither and reads them from the catalog dataset, which then cannot be deleted.
 * Wallet events, intents, orders and positions are stored under the run either way.
 */
enum class RunStorage {
    COPY,
    DATASET_REFERENCE,
}
