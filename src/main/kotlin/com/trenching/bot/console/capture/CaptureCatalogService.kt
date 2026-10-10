package com.trenching.bot.console.capture

import com.trenching.bot.replay.ReplayDatasetCatalog
import com.trenching.bot.replay.domain.DatasetRegistration

class CaptureCatalogService(private val store: CaptureFileStore, private val operations: CaptureOperationService, private val catalog: ReplayDatasetCatalog) {
    fun register(id: String): DatasetRegistration = synchronized(store) {
        operations.requireIdle()
        val session=store.session(id)
        val bytes=operations.artifact(id,"dataset")
        val registered=bytes.inputStream().use { catalog.register(session.name,it) }
        store.save(session.copy(datasetId=registered.dataset.id))
        registered
    }
}
