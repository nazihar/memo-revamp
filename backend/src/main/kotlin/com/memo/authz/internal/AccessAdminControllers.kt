package com.memo.authz.internal

import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.authz.SignedIn
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.ProblemTypes
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.identity.StepUp
import com.memo.system.Idempotency
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Instant
import java.util.UUID

@Schema(name = "PermissionInfo")
data class PermissionResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "org.manage") val key: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Can be limited to a unit subtree") val scopable: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Holders count as administrators (always MFA)")
    val privileged: Boolean,
)

@Schema(name = "Role")
data class RoleResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(description = "Key of a built-in role; absent for custom roles") val builtIn: String? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    val description: String? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val permissions: List<String>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "How many grants use it") val grants: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Send as If-Match on changes") val version: Long,
)

@Schema(name = "RoleRequest")
data class RoleRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 100) val name: String,
    @field:Schema(maxLength = 500) val description: String? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Catalogue keys") val permissions: List<String>,
)

@Schema(name = "Grant")
data class GrantResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val roleId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val roleName: String,
    val builtIn: String? = null,
    @field:Schema(description = "Unit subtree; absent for the whole organisation") val scopeUnitId: UUID? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val grantedAt: Instant,
)

@Schema(name = "GrantRequest")
data class GrantRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val roleId: UUID,
    @field:Schema(description = "Limit to a unit subtree") val scopeUnitId: UUID? = null,
)

@Schema(name = "ClassificationLevel")
data class ClassificationLevelResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "0 = lowest") val rank: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    val description: String? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Send as If-Match on renames") val version: Long,
)

@Schema(name = "ClassificationLevelRequest")
data class ClassificationLevelRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 100) val name: String,
    @field:Schema(maxLength = 500) val description: String? = null,
)

@Schema(name = "ClassificationOrderRequest")
data class ClassificationOrderRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Every level id, lowest first") val levelIds: List<UUID>,
)

@Schema(name = "UserAccess")
data class UserAccessResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val grants: List<GrantResponse>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val clearance: ClassificationLevelResponse,
)

@Schema(name = "ClearanceRequest")
data class ClearanceRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val levelId: UUID,
)

/** Roles and the permission catalogue (design E04 §6). Changes need a fresh step-up. */
@RestController
@RequestMapping("/api/v1/authz")
@Tag(name = "authz")
class RolesController(
    private val admin: RoleAdmin,
    private val catalog: RoleCatalog,
    private val stepUp: StepUp,
    private val idempotency: Idempotency,
    private val transactions: TenantTransactions,
    private val json: JsonMapper,
) {
    @GetMapping("/permissions")
    @RequiresPermission(Permission.AUTHZ_ROLES_MANAGE)
    @Operation(operationId = "listPermissions", summary = "The permission catalogue")
    fun permissions(): List<PermissionResponse> = Permission.entries.map { PermissionResponse(it.key, it.scopable, it.privileged) }

    @GetMapping("/roles")
    @RequiresPermission(Permission.AUTHZ_ROLES_MANAGE)
    @Operation(operationId = "listRoles", summary = "Built-in and custom roles")
    fun roles(): ResponseEntity<List<RoleResponse>> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(read { catalog.all().map { it.toResponse() } })

    @PostMapping("/roles")
    @RequiresPermission(Permission.AUTHZ_ROLES_MANAGE)
    @Operation(operationId = "createRole", summary = "Create a custom role")
    fun create(
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: RoleRequest,
    ): ResponseEntity<RoleResponse> {
        stepUp.require()
        val role =
            write {
                val id =
                    idempotency.once("authz.role.create", key, json.writeValueAsString(body)) {
                        admin
                            .create(
                                name(body.name),
                                body.description?.trim()?.ifEmpty { null },
                                permissions(body.permissions),
                            ).id
                            .toString()
                    }
                admin.get(UUID.fromString(id))
            }
        return ResponseEntity.created(URI.create("/api/v1/authz/roles/${role.id}")).eTag(etagOf(role.version)).body(role.toResponse())
    }

    @PutMapping("/roles/{id}")
    @RequiresPermission(Permission.AUTHZ_ROLES_MANAGE)
    @Operation(operationId = "updateRole", summary = "Change a custom role")
    fun update(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: RoleRequest,
    ): ResponseEntity<RoleResponse> {
        val version = ifMatchVersion(ifMatch)
        stepUp.require()
        val role =
            write { admin.update(id, version, name(body.name), body.description?.trim()?.ifEmpty { null }, permissions(body.permissions)) }
        return ResponseEntity.ok().eTag(etagOf(role.version)).body(role.toResponse())
    }

    @DeleteMapping("/roles/{id}")
    @RequiresPermission(Permission.AUTHZ_ROLES_MANAGE)
    @Operation(operationId = "deleteRole", summary = "Remove an unused custom role")
    fun delete(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<Void> {
        val version = ifMatchVersion(ifMatch)
        stepUp.require()
        write { admin.delete(id, version) }
        return ResponseEntity.noContent().build()
    }

    private fun name(value: String) = value.trim().also { require(it.isNotEmpty()) { "A name is required." } }

    private fun permissions(keys: List<String>) = keys.map { requireNotNull(Permission.ofKey(it)) { "Unknown permission $it." } }.toSet()

    private fun <T> read(block: () -> T): T = transactions.readInTenant(TenantContext.current(), block)

    private fun <T> write(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)
}

/** A user's grants and clearance (design E04 §6). Changes need a fresh step-up. */
@RestController
@RequestMapping("/api/v1/authz")
@Tag(name = "authz")
class GrantsController(
    private val grants: GrantAdmin,
    private val classification: ClassificationAdmin,
    private val stepUp: StepUp,
    private val transactions: TenantTransactions,
) {
    @GetMapping("/users/{id}/access")
    @RequiresPermission(Permission.AUTHZ_ROLES_MANAGE)
    @Operation(operationId = "getUserAccess", summary = "A user's role grants and clearance")
    fun access(
        @PathVariable id: UUID,
    ): ResponseEntity<UserAccessResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            transactions.readInTenant(TenantContext.current()) {
                UserAccessResponse(grants.grantsOf(id).map { it.toResponse() }, classification.clearanceOf(id).toResponse())
            },
        )

    @PostMapping("/users/{id}/grants")
    @RequiresPermission(Permission.AUTHZ_ROLES_MANAGE)
    @Operation(operationId = "grantRole", summary = "Give a user a role, for the organisation or a unit subtree")
    fun grant(
        @PathVariable id: UUID,
        @RequestBody body: GrantRequest,
    ): ResponseEntity<GrantResponse> {
        stepUp.require()
        val grant = transactions.inTenant(TenantContext.current()) { grants.grant(id, body.roleId, body.scopeUnitId) }
        return ResponseEntity.created(URI.create("/api/v1/authz/grants/${grant.id}")).body(grant.toResponse())
    }

    @DeleteMapping("/grants/{id}")
    @RequiresPermission(Permission.AUTHZ_ROLES_MANAGE)
    @Operation(operationId = "revokeGrant", summary = "Revoke a role grant")
    fun revoke(
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        stepUp.require()
        transactions.inTenant(TenantContext.current()) { grants.revoke(id) }
        return ResponseEntity.noContent().build()
    }

    @PutMapping("/users/{id}/clearance")
    @RequiresPermission(Permission.AUTHZ_CLASSIFICATIONS_MANAGE)
    @Operation(operationId = "setClearance", summary = "Set a user's clearance level")
    fun clearance(
        @PathVariable id: UUID,
        @RequestBody body: ClearanceRequest,
    ): ClassificationLevelResponse {
        stepUp.require()
        return transactions.inTenant(TenantContext.current()) { classification.setClearance(id, body.levelId) }.toResponse()
    }
}

/** Classification levels (design E04 §6): readable by every signed-in user; changes need a fresh step-up. */
@RestController
@RequestMapping("/api/v1/authz/classification-levels")
@Tag(name = "authz")
class ClassificationController(
    private val classification: ClassificationAdmin,
    private val stepUp: StepUp,
    private val idempotency: Idempotency,
    private val transactions: TenantTransactions,
    private val json: JsonMapper,
) {
    @GetMapping
    @SignedIn
    @Operation(operationId = "listClassificationLevels", summary = "Classification levels, lowest first")
    fun list(): List<ClassificationLevelResponse> =
        transactions.readInTenant(TenantContext.current()) { classification.list().map { it.toResponse() } }

    @PostMapping
    @RequiresPermission(Permission.AUTHZ_CLASSIFICATIONS_MANAGE)
    @Operation(operationId = "createClassificationLevel", summary = "Add a level above the others")
    fun create(
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: ClassificationLevelRequest,
    ): ResponseEntity<ClassificationLevelResponse> {
        stepUp.require()
        val level =
            transactions.inTenant(TenantContext.current()) {
                val id =
                    idempotency.once("authz.classification.create", key, json.writeValueAsString(body)) {
                        classification.create(name(body.name), body.description?.trim()?.ifEmpty { null }).id.toString()
                    }
                classification.list().first { it.id == UUID.fromString(id) }
            }
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etagOf(level.version)).body(level.toResponse())
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.AUTHZ_CLASSIFICATIONS_MANAGE)
    @Operation(operationId = "renameClassificationLevel", summary = "Rename a level")
    fun rename(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: ClassificationLevelRequest,
    ): ResponseEntity<ClassificationLevelResponse> {
        val version = ifMatchVersion(ifMatch)
        stepUp.require()
        val level =
            transactions.inTenant(TenantContext.current()) {
                classification.rename(id, version, name(body.name), body.description?.trim()?.ifEmpty { null })
            }
        return ResponseEntity.ok().eTag(etagOf(level.version)).body(level.toResponse())
    }

    @PostMapping("/order")
    @RequiresPermission(Permission.AUTHZ_CLASSIFICATIONS_MANAGE)
    @Operation(operationId = "reorderClassificationLevels", summary = "Put the levels in a new order, lowest first")
    fun reorder(
        @RequestBody body: ClassificationOrderRequest,
    ): List<ClassificationLevelResponse> {
        stepUp.require()
        return transactions.inTenant(TenantContext.current()) { classification.reorder(body.levelIds).map { it.toResponse() } }
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permission.AUTHZ_CLASSIFICATIONS_MANAGE)
    @Operation(operationId = "deleteClassificationLevel", summary = "Remove a level nobody is cleared to")
    fun delete(
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        stepUp.require()
        transactions.inTenant(TenantContext.current()) { classification.delete(id) }
        return ResponseEntity.noContent().build()
    }

    private fun name(value: String) = value.trim().also { require(it.isNotEmpty()) { "A name is required." } }
}

/** Problems of the authz endpoints: unknown ids are 404, invalid input 400 `validation`. */
@RestControllerAdvice(basePackageClasses = [AuthzProblems::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class AuthzProblems {
    @ExceptionHandler(AuthzNotFoundException::class)
    fun notFound(ex: AuthzNotFoundException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message ?: "Not found"))

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(ex: IllegalArgumentException): ResponseEntity<ProblemDetail> =
        ResponseEntity.badRequest().body(ProblemTypes.of(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION, ex.message ?: "Invalid request."))
}

fun Role.toResponse() = RoleResponse(id, builtIn?.key, name, description, permissions.map { it.key }.sorted(), grants, version)

fun GrantRow.toResponse() = GrantResponse(id, roleId, roleName, builtIn, scopeUnit, grantedAt)

fun ClassificationLevel.toResponse() = ClassificationLevelResponse(id, rank, name, description, version)
