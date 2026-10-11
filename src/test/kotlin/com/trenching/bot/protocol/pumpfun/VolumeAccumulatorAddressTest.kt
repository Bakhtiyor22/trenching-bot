package com.trenching.bot.protocol.pumpfun

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.Test

class VolumeAccumulatorAddressTest {
    @Test
    fun `derives the Pump program accumulator that the recorded buy passes`() {
        assertEquals("Hj2hjN1EAe5pv676kUMf14zbj1ioWEHaiukbHDqw2P3D", VolumeAccumulatorAddress.pump(DECU))
    }

    /** Cross-checked offline with an independent implementation of find_program_address. */
    @Test
    fun `derives the PumpSwap accumulator from the same seeds under the PumpSwap program`() {
        assertEquals("GhhDLG4uBDVSnEAXwBpzfm4JR9E9HweSAhrCCGPjMvgp", VolumeAccumulatorAddress.pumpSwap(DECU))
        assertNotEquals(VolumeAccumulatorAddress.pump(DECU), VolumeAccumulatorAddress.pumpSwap(DECU))
    }

    private companion object {
        const val DECU = "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9"
    }
}
