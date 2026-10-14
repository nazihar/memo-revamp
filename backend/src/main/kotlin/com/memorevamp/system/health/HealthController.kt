package com.memorevamp.system.health

import com.memorevamp.shared.health.HealthStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@Schema(name = "Health", description = "Public platform status. Contains only component names and states.")
data class HealthResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val status: HealthStatus,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val checkedAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val components: List<ComponentHealthResponse>,
)

@Schema(name = "ComponentHealth")
data class ComponentHealthResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val name: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val status: ComponentStatus,
)

@RestController
@RequestMapping("/api/v1/health")
@Tag(name = "system")
class HealthController(
    private val healthService: HealthService,
) {
    @GetMapping
    @Operation(
        operationId = "getHealth",
        summary = "Platform status",
        description = "Anonymous. Status per dependency, no versions, hosts or error details. Cached for a few seconds.",
        responses = [
            ApiResponse(responseCode = "200", description = "UP or DEGRADED"),
            ApiResponse(
                responseCode = "503",
                description = "DOWN (the database is unreachable)",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = HealthResponse::class))],
            ),
        ],
    )
    fun health(): ResponseEntity<HealthResponse> {
        val snapshot = healthService.current()
        val body =
            HealthResponse(
                status = snapshot.status,
                checkedAt = snapshot.checkedAt,
                components = snapshot.components.map { ComponentHealthResponse(it.name, it.status) },
            )
        val status = if (snapshot.status == HealthStatus.DOWN) HttpStatus.SERVICE_UNAVAILABLE else HttpStatus.OK
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body)
    }
}
