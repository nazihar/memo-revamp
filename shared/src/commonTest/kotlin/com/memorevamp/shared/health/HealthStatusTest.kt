package com.memorevamp.shared.health

import kotlin.test.Test
import kotlin.test.assertEquals

class HealthStatusTest {
    @Test
    fun `worst of nothing is up`() {
        assertEquals(HealthStatus.UP, HealthStatus.worstOf(emptyList()))
    }

    @Test
    fun `down outranks degraded which outranks up`() {
        assertEquals(HealthStatus.DEGRADED, HealthStatus.worstOf(listOf(HealthStatus.UP, HealthStatus.DEGRADED)))
        assertEquals(HealthStatus.DOWN, HealthStatus.worstOf(listOf(HealthStatus.DEGRADED, HealthStatus.DOWN, HealthStatus.UP)))
    }
}
