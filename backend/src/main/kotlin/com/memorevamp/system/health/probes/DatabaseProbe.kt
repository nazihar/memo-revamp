package com.memorevamp.system.health.probes

import com.memorevamp.system.health.ComponentProbe
import com.memorevamp.system.health.ComponentStatus
import com.memorevamp.system.info.SystemInfoRepository
import org.springframework.stereotype.Component

@Component
class DatabaseProbe(
    private val systemInfo: SystemInfoRepository,
) : ComponentProbe {
    override val name = "database"
    override val critical = true

    override fun check(): ComponentStatus =
        if (systemInfo.schemaMarker() == SystemInfoRepository.EXPECTED_MARKER) ComponentStatus.UP else ComponentStatus.DOWN
}
