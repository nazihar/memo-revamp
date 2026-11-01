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

/** Who holds which position when (design E04 §6). Changes need `org.manage` on the position's unit. */
@RestController
@Tag(name = "org")
class AssignmentsController(
    private val assignments: Assignments,
    private val views: OrgViews,
    private val calendar: OrgCalendar,
    private val idempotency: Idempotency,
    private val transactions: TenantTransactions,
    private val json: JsonMapper,
) {
    @PostMapping("/api/v1/org/positions/{id}/assignments")
    @RequiresPermission(Permission.ORG_MANAGE)
    @Operation(operationId = "assignPosition", summary = "Assign a person to a position from a date")
    fun assign(
        @PathVariable id: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: AssignRequest,
    ): ResponseEntity<AssignmentResponse> {
        val created =
            write {
                val assignment =
                    idempotency.once("org.assignment.create", key, json.writeValueAsString(body) + id) {
                        assignments.assign(id, body.userId, Period(body.validFrom, body.validTo)).id.toString()
                    }
                views.assignment(UUID.fromString(assignment))
            }
        return ResponseEntity.created(URI.create("/api/v1/org/assignments/${created.id}")).eTag(etagOf(created.version)).body(created)
    }

    @PutMapping("/api/v1/org/assignments/{id}")
    @RequiresPermission(Permission.ORG_MANAGE)
    @Operation(operationId = "updateAssignment", summary = "End an assignment or correct its dates")
    fun update(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: PeriodRequest,
    ): ResponseEntity<AssignmentResponse> {
        val version = ifMatchVersion(ifMatch)
        val updated =
            write {
                assignments.changeDates(id, version, Period(body.validFrom, body.validTo))
                views.assignment(id)
            }
        return ResponseEntity.ok().eTag(etagOf(updated.version)).body(updated)
    }

    @PostMapping("/api/v1/org/assignments/{id}/cancel")
    @RequiresPermission(Permission.ORG_MANAGE)
    @Operation(operationId = "cancelAssignment", summary = "Cancel an assignment recorded in error")
    fun cancel(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<AssignmentResponse> {
        val version = ifMatchVersion(ifMatch)
        val cancelled =
            write {
                assignments.cancel(id, version)
                views.assignment(id)
            }
        return ResponseEntity.ok().eTag(etagOf(cancelled.version)).body(cancelled)
    }

    @GetMapping("/api/v1/org/positions/{id}/holder")
    @RequiresPermission(Permission.DIRECTORY_READ)
    @Operation(operationId = "getPositionHolder", summary = "Who held a position on a date (default today); 204 when vacant")
    fun holder(
        @PathVariable id: UUID,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?,
    ): ResponseEntity<AssignmentResponse> {
        val holder = read { views.holderOn(id, date ?: calendar.today()) }
        return if (holder ==
            null
        ) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(holder)
        }
    }

    @GetMapping("/api/v1/org/users/{id}/assignments")
    @RequiresPermission(Permission.DIRECTORY_READ)
    @Operation(operationId = "listUserAssignments", summary = "A person's positions over time, newest first")
    fun ofUser(
        @PathVariable id: UUID,
    ): ResponseEntity<List<AssignmentResponse>> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            read {
                views.assignmentsOf(id)
            },
        )

    private fun <T> read(block: () -> T): T = transactions.readInTenant(TenantContext.current(), block)

    private fun <T> write(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)
}
