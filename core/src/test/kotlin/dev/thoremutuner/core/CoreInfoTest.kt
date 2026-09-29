package dev.thoremutuner.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CoreInfoTest {
    @Test
    fun batteryEnergyMatchesSpec() {
        assertEquals(22.2, CoreInfo.THOR_BATTERY_WH, 1e-9)
    }
}
