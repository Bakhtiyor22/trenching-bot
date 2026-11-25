package com.trenching.bot.papertrading

import com.trenching.bot.papertrading.application.PaperOrderStoreService
import com.trenching.bot.papertrading.infrastructure.persistence.PaperFillRepository
import com.trenching.bot.papertrading.infrastructure.persistence.PaperOrderRepository
import com.trenching.bot.papertrading.mapper.PaperOrderMapper
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

class PaperOrderStoreServiceEmptyRunIdsTest {
    private val orders = mock(PaperOrderRepository::class.java)
    private val service = PaperOrderStoreService(orders, mock(PaperFillRepository::class.java), mock(PaperOrderMapper::class.java))

    @Test
    fun `an empty list of runs makes no query`() {
        assertEquals(emptyMap(), service.rejectionsByRun(emptyList()))

        verifyNoInteractions(orders)
    }
}
