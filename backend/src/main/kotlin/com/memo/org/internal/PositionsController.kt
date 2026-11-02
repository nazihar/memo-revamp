package com.memo.org.internal

import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.system.Idempotency
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.LocalDate
import java.util.UUID

/** Positions (design E04 §6): reads need `directory.read`, changes `org.manage` on the position's unit. */
@RestController
@Tag(name = "org")
class PositionsController(
    private val positions: Positions,
    private val views: OrgViews,
    private val calendar: OrgCalendar,
    private val idempotency: Idempotency,
    private val transactions: TenantTransactions,
    private val json: JsonMapper,
) {
    @GetMapping("/api/v1/org/units/{id}/positions")
    @RequiresPermission(Permission.DIRECTORY_READ)
    @Operation(operationId = "listUnitPositions", summary = "Positions of a unit with their holders on a date (default today)")
    fun ofUnit(
        @PathVariable id: UUID,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) asOf: LocalDate?,
    ): ResponseEntity<List<PositionWithHolderResponse>> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(read { views.positionsOf(id, asOf ?: calendar.today()) })

    @GetMapping("/api/v1/org/positions/{id}")
    @RequiresPermission(Permission.DIRECTORY_READ)
    @Operation(operationId = "getPosition", summary = "A position with its holders and reporting lines over time")
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<PositionDetailResponse> {
        val body = read { views.positionDetail(id) }
        return ResponseEntity
            .ok()
            .eTag(etagOf(body.position.version))
            .cacheControl(CacheControl.noStore())
            .body(body)
    }

    @PostMapping("/api/v1/org/positions")
    @RequiresPermission(Permission.ORG_MANAGE)
    @Operation(operationId = "createPosition", summary = "Create a position in a unit")
    fun create(
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: PositionRequest,
    ): ResponseEntity<PositionResponse> {
        val unitId = requireNotNull(body.unitId) { "A unit is required." }
        val position =
            write {
                val id =
                    idempotency.once("org.position.create", key, json.writeValueAsString(body)) {
                        positions.create(unitId, optionalText(body.code), requiredText(body.title), body.head, body.validFrom).id.toString()
                    }
                positions.get(UUID.fromString(id))
            }
        return ResponseEntity
            .created(URI.create("/api/v1/org/positions/${position.id}"))
            .eTag(etagOf(position.version))
            .body(position.toResponse())
    }

    @PutMapping("/api/v1/org/positions/{id}")
    @RequiresPermission(Permission.ORG_MANAGE)
    @Operation(operationId = "updatePosition", summary = "Retitle or recode a position, or mark it as head of its unit")
    fun update(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: PositionRequest,
    ): ResponseEntity<PositionResponse> {
        val version = ifMatchVersion(ifMatch)
        val position = write { positions.update(id, version, optionalText(body.code), requiredText(body.title), body.head) }
        return ResponseEntity.ok().eTag(etagOf(position.version)).body(position.toResponse())
    }

    @PostMapping("/api/v1/org/positions/{id}/close")
    @RequiresPermission(Permission.ORG_MANAGE)
    @Operation(operationId = "closePosition", summary = "Close a position")
    fun close(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: CloseRequest,
    ): ResponseEntity<PositionResponse> {
        val version = ifMatchVersion(ifMatch)
        val position = write { positions.close(id, version, body.validTo) }
        return ResponseEntity.ok().eTag(etagOf(position.version)).body(position.toResponse())
    }

    private fun <T> read(block: () -> T): T = transactions.readInTenant(TenantContext.current(), block)

    private fun <T> write(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)
}
