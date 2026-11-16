package com.trenching.bot.console.api

import com.trenching.bot.common.PageResult
import com.trenching.bot.console.application.DatasetConsoleService
import com.trenching.bot.console.application.DatasetSourceService
import com.trenching.bot.console.domain.CaptureSourceLookup
import com.trenching.bot.console.mapper.ConsoleErrorMapperImpl
import com.trenching.bot.console.mapper.DatasetResponseMapperImpl
import com.trenching.bot.console.mapper.ObservationResponseMapperImpl
import com.trenching.bot.replay.ReplayDatasetCatalog
import com.trenching.bot.replay.domain.DatasetInUseException
import com.trenching.bot.replay.domain.DatasetRegistration
import com.trenching.bot.replay.domain.DatasetSummary
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.domain.SampleDataset
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.json.JsonMapper

class DatasetControllerTest {
    private val json = JsonMapper.builder().build()
    private val catalog = Catalog()
    private val mvc: MockMvc = MockMvcBuilders
        .standaloneSetup(DatasetController(
            DatasetConsoleService(catalog, SimpleMeterRegistry(), DatasetSourceService(catalog, CaptureSourceLookup.ABSENT)),
            DatasetResponseMapperImpl(), ObservationResponseMapperImpl()))
        .setControllerAdvice(ConsoleExceptionHandler(ConsoleErrorMapperImpl()))
        .build()

    @Test
    fun `deleting a dataset that reference runs still use is a 409 naming the run count`() {
        val response = mvc.perform(delete("/api/datasets/$IN_USE")).andReturn().response

        assertEquals(409, response.status, response.contentAsString)
        val body = json.readTree(response.contentAsString)
        assertEquals("DATASET_IN_USE", body["code"].asString())
        assertTrue(body["message"].asString().contains("datasetId=$IN_USE"), body["message"].asString())
        assertTrue(body["message"].asString().contains("referenceRuns=3"), body["message"].asString())
        assertEquals("{}", body["fields"].toString())
    }

    @Test
    fun `deleting a dataset no reference run uses succeeds`() {
        assertEquals(204, mvc.perform(delete("/api/datasets/$UNUSED")).andReturn().response.status)
        assertEquals(listOf(UNUSED), catalog.deleted)
    }

    @Test
    fun `a search text is trimmed and sent to the catalog search instead of the plain page`() {
        val response = mvc.perform(get("/api/datasets").param("q", "  alpha ").param("page", "1").param("size", "5")).andReturn().response

        assertEquals(200, response.status, response.contentAsString)
        assertEquals(listOf(Triple("alpha", 1, 5)), catalog.searches)
        assertEquals(0, json.readTree(response.contentAsString)["total"].asInt())
    }

    @Test
    fun `a search text outside 2 to 100 characters is a 400 INVALID_REQUEST naming q`() {
        listOf("a", " ", "x".repeat(101)).forEach { text ->
            val response = mvc.perform(get("/api/datasets").param("q", text)).andReturn().response

            assertEquals(400, response.status, text)
            val body = json.readTree(response.contentAsString)
            assertEquals("INVALID_REQUEST", body["code"].asString())
            assertEquals(listOf("q"), body["fields"].propertyNames().toList())
        }
        assertEquals(emptyList(), catalog.searches)
    }

    private class Catalog : ReplayDatasetCatalog {
        val deleted = mutableListOf<String>()
        val searches = mutableListOf<Triple<String, Int, Int>>()

        override fun delete(id: String): Boolean {
            if (id == IN_USE) throw DatasetInUseException(id, 3)
            deleted += id
            return true
        }

        override fun register(name: String, json: InputStream): DatasetRegistration = error("unused")
        override fun importSample(sample: SampleDataset): DatasetRegistration = error("unused")
        override fun page(page: Int, size: Int): PageResult<DatasetSummary> = error("unused")
        override fun search(text: String, page: Int, size: Int): PageResult<DatasetSummary> {
            searches += Triple(text, page, size)
            return PageResult(emptyList(), page, size, 0)
        }
        override fun find(id: String): DatasetSummary? = error("unused")
        override fun load(id: String): ReplayDataset = error("unused")
    }

    private companion object {
        const val IN_USE = "dataset-in-use"
        const val UNUSED = "dataset-unused"
    }
}
