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
