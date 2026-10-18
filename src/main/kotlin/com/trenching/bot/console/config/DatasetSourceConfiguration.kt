package com.trenching.bot.console.config

import com.trenching.bot.console.application.DatasetSourceService
import com.trenching.bot.console.domain.CaptureSourceLookup
import com.trenching.bot.replay.ReplayDatasetCatalog
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Run and dataset views name their source in every profile; without [CaptureConfiguration] no capture is ever found. */
@Configuration(proxyBeanMethods = false)
class DatasetSourceConfiguration {
    @Bean
    fun datasetSourceService(catalog: ReplayDatasetCatalog, captures: ObjectProvider<CaptureSourceLookup>): DatasetSourceService =
        DatasetSourceService(catalog, captures.getIfAvailable { CaptureSourceLookup.ABSENT })
}
