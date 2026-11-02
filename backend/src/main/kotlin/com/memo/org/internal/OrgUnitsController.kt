package com.memo.org.internal

import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.authz.RequiresPermission
import com.memo.common.authz.Resource
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.system.Idempotency
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.LocalDate
import java.util.UUID

/**
 * The units tree (design E04 §6). Reads need `directory.read`; changes need `org.manage` for the units they touch
 * (checked in [OrgUnits]). Updates need `If-Match`, creating needs an `Idempotency-Key`.
 */
@RestController
@RequestMapping("/api/v1/org/units")
@Tag(name = "org")
class OrgUnitsController(
    private val units: OrgUnits,
    private val store: UnitStore,
    private val calendar: OrgCalendar,
    private val policy: Policy,
    private val idempotency: Idempotency,
    private val transactions: TenantTransactions,
    private val json: JsonMapper,
) {
    @GetMapping
    @RequiresPermission(Permission.DIRECTORY_READ)
    @Operation(
        operationId = "listOrgUnits",
        summary = "The units tree as a flat list",
        description = "Units valid on `asOf` (default today), or every unit with `includeClosed`. Clients build the tree from parentId.",
    )
    fun list(
        @Parameter(description = "Date the tree is shown for") @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) asOf: LocalDate?,
        @RequestParam(required = false, defaultValue = "false") includeClosed: Boolean,
    ): ResponseEntity<List<OrgUnitResponse>> {
        val body =
            read {
                val date = asOf ?: calendar.today()
                store.units().filter { includeClosed || it.openOn(date) }.map { it.toResponse() }
            }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permission.DIRECTORY_READ)
    @Operation(operationId = "getOrgUnit", summary = "A unit with its path")
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<OrgUnitDetailResponse> {
        val body =
            read {
                val unit = units.unit(id)
                val all = store.units().associateBy { it.id }
                OrgUnitDetailResponse(
                    unit.toResponse(),
                    unit.ancestors.mapNotNull { all[it]?.toResponse() },
                    policy.allows(Permission.ORG_MANAGE, Resource.Unit(id)),
                )
            }
        return ResponseEntity
            .ok()
            .eTag(etagOf(body.unit.version))
            .cacheControl(CacheControl.noStore())
            .body(body)
    }

    @PostMapping
    @RequiresPermission(Permission.ORG_MANAGE)
    @Operation(operationId = "createOrgUnit", summary = "Create a unit")
    fun create(
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: CreateOrgUnitRequest,
    ): ResponseEntity<OrgUnitResponse> {
        val unit =
            write {
                val id =
                    idempotency.once("org.unit.create", key, json.writeValueAsString(body)) {
                        units
                            .createUnit(
                                body.legalEntityId,
                                body.parentId,
                                body.code.trim(),
                                requiredText(body.name),
                                body.validFrom,
                            ).id
                            .toString()
                    }
                units.unit(UUID.fromString(id))
            }
        return ResponseEntity.created(URI.create("/api/v1/org/units/${unit.id}")).eTag(etagOf(unit.version)).body(unit.toResponse())
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.ORG_MANAGE)
    @Operation(operationId = "updateOrgUnit", summary = "Rename or recode a unit")
    fun update(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: UpdateOrgUnitRequest,
    ): ResponseEntity<OrgUnitResponse> {
        val version = ifMatchVersion(ifMatch)
        val unit = write { units.updateUnit(id, version, body.code.trim(), requiredText(body.name)) }
        return ResponseEntity.ok().eTag(etagOf(unit.version)).body(unit.toResponse())
    }

    @PostMapping("/{id}/move")
    @RequiresPermission(Permission.ORG_MANAGE)
    @Operation(operationId = "moveOrgUnit", summary = "Move a unit, with its sub-units, under another parent")
    fun move(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: MoveOrgUnitRequest,
    ): ResponseEntity<OrgUnitResponse> {
        val version = ifMatchVersion(ifMatch)
        val unit = write { units.moveUnit(id, version, body.parentId) }
        return ResponseEntity.ok().eTag(etagOf(unit.version)).body(unit.toResponse())
    }

    @PostMapping("/{id}/close")
    @RequiresPermission(Permission.ORG_MANAGE)
    @Operation(operationId = "closeOrgUnit", summary = "Close a unit")
    fun close(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: CloseRequest,
    ): ResponseEntity<OrgUnitResponse> {
        val version = ifMatchVersion(ifMatch)
        val unit = write { units.closeUnit(id, version, body.validTo) }
        return ResponseEntity.ok().eTag(etagOf(unit.version)).body(unit.toResponse())
    }

    private fun <T> read(block: () -> T): T = transactions.readInTenant(TenantContext.current(), block)

    private fun <T> write(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)
}
