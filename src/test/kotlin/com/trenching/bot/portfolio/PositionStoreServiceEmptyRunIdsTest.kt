package com.trenching.bot.portfolio

import com.trenching.bot.papertrading.PaperOrderStore
import com.trenching.bot.portfolio.application.PositionStoreService
import com.trenching.bot.portfolio.infrastructure.persistence.PositionRepository
import com.trenching.bot.portfolio.mapper.PositionMapper
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

class PositionStoreServiceEmptyRunIdsTest {
    private val repository = mock(PositionRepository::class.java)
    private val service = PositionStoreService(repository, mock(PaperOrderStore::class.java), mock(PositionMapper::class.java))

    @Test
    fun `an empty list of runs makes no query`() {
        assertEquals(emptyMap(), service.outcomesByRun(emptyList()))

        verifyNoInteractions(repository)
    }
}
