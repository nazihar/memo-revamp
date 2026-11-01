package com.memo.org.internal

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate
import java.util.UUID

@Schema(name = "LegalEntity")
data class LegalEntityResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    val shortName: String? = null,
    val registrationNumber: String? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val validFrom: LocalDate,
    val validTo: LocalDate? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Send as If-Match on updates") val version: Long,
)

@Schema(name = "LegalEntityRequest")
data class LegalEntityRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200) val name: String,
    @field:Schema(maxLength = 50) val shortName: String? = null,
    @field:Schema(maxLength = 100) val registrationNumber: String? = null,
    @field:Schema(description = "Creation only; defaults to today") val validFrom: LocalDate? = null,
)

@Schema(name = "OrgUnit")
data class OrgUnitResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val legalEntityId: UUID,
    val parentId: UUID? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val code: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val validFrom: LocalDate,
    val validTo: LocalDate? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Send as If-Match on updates") val version: Long,
)

@Schema(name = "OrgUnitDetail")
data class OrgUnitDetailResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val unit: OrgUnitResponse,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Ancestors, root first") val path: List<OrgUnitResponse>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Whether the signed-in user may change this unit")
    val canManage: Boolean,
)

@Schema(name = "CreateOrgUnitRequest")
data class CreateOrgUnitRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val legalEntityId: UUID,
    @field:Schema(description = "Absent for a root unit of the legal entity") val parentId: UUID? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 30) val code: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200) val name: String,
    @field:Schema(description = "Defaults to today") val validFrom: LocalDate? = null,
)

@Schema(name = "UpdateOrgUnitRequest")
data class UpdateOrgUnitRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 30) val code: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200) val name: String,
)

@Schema(name = "MoveOrgUnitRequest")
data class MoveOrgUnitRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val parentId: UUID,
)

@Schema(name = "CloseRequest")
data class CloseRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "First day it no longer exists") val validTo: LocalDate,
)

fun LegalEntity.toResponse() = LegalEntityResponse(id, name, shortName, registrationNumber, validFrom, validTo, version)

fun OrgUnit.toResponse() = OrgUnitResponse(id, legalEntityId, parentId, code, name, validFrom, validTo, version)

@Schema(name = "OrgPosition")
data class PositionResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val unitId: UUID,
    val code: String? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val title: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Head of its unit") val head: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val validFrom: LocalDate,
    val validTo: LocalDate? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Send as If-Match on updates") val version: Long,
)

@Schema(name = "OrgPerson")
data class PersonResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val displayName: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val email: String,
)

@Schema(name = "OrgPositionWithHolder")
data class PositionWithHolderResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val position: PositionResponse,
    @field:Schema(description = "Holder on the requested date; absent when vacant") val holder: PersonResponse? = null,
    @field:Schema(description = "Start of the holder's assignment") val holderSince: LocalDate? = null,
)

@Schema(name = "OrgAssignment")
data class AssignmentResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val positionId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val person: PersonResponse,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val validFrom: LocalDate,
    val validTo: LocalDate? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val status: RecordStatus,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Send as If-Match on updates") val version: Long,
)

@Schema(name = "OrgReportingLine")
data class ReportingLineResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val positionId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val reportsTo: PositionResponse,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val validFrom: LocalDate,
    val validTo: LocalDate? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val status: RecordStatus,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Send as If-Match on updates") val version: Long,
)

@Schema(name = "OrgPositionDetail")
data class PositionDetailResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val position: PositionResponse,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val unit: OrgUnitResponse,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Newest first, cancelled ones included")
    val assignments: List<AssignmentResponse>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Newest first, cancelled ones included")
    val reportingLines: List<ReportingLineResponse>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Whether the signed-in user may change it")
    val canManage: Boolean,
)

@Schema(name = "PositionRequest")
data class PositionRequest(
    @field:Schema(description = "Creation only") val unitId: UUID? = null,
    @field:Schema(maxLength = 30) val code: String? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200) val title: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val head: Boolean = false,
    @field:Schema(description = "Creation only; defaults to today") val validFrom: LocalDate? = null,
)

@Schema(name = "PeriodRequest")
data class PeriodRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val validFrom: LocalDate,
    @field:Schema(description = "First day it no longer applies; absent for open-ended") val validTo: LocalDate? = null,
)

@Schema(name = "AssignRequest")
data class AssignRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val userId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val validFrom: LocalDate,
    val validTo: LocalDate? = null,
)

@Schema(name = "ReportingLineRequest")
data class ReportingLineRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val reportsToPositionId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val validFrom: LocalDate,
    val validTo: LocalDate? = null,
)

@Schema(name = "OrgHistoryEntry")
data class HistoryEntryResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val occurredAt: java.time.Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "org.unit.moved") val action: String,
    @field:Schema(description = "Display name of the person who made the change; absent for the system") val actor: String? = null,
    val before: Map<String, Any?>? = null,
    val after: Map<String, Any?>? = null,
)

fun OrgPosition.toResponse() = PositionResponse(id, unitId, code, title, head, validFrom, validTo, version)
