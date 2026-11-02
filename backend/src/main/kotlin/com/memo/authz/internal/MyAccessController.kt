package com.memo.authz.internal

import com.memo.common.authz.Policy
import com.memo.common.authz.SignedIn
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Schema(name = "MyPermission")
data class MyPermissionResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "org.manage")
    val key: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Held for the whole organisation")
    val tenantWide: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Unit subtrees it is held for, when not tenant-wide")
    val scopeUnitIds: List<UUID>,
)

@Schema(name = "MyClearance")
data class MyClearanceResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val rank: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val name: String,
)

@Schema(name = "MyAccess")
data class MyAccessResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val permissions: List<MyPermissionResponse>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val clearance: MyClearanceResponse,
)

/** What the signed-in user may do (ADR-0027), so the web client shows only what the API would allow. */
@RestController
@RequestMapping("/api/v1/me/access")
@Tag(name = "me")
@SignedIn
class MyAccessController(
    private val policy: Policy,
    private val levels: ClassificationStore,
    private val transactions: TenantTransactions,
) {
    @GetMapping
    @Operation(operationId = "getMyAccess", summary = "My permissions and clearance")
    fun access(): ResponseEntity<MyAccessResponse> {
        val byPermission = policy.grants().groupBy { it.permission }
        val permissions =
            byPermission.map { (permission, grants) ->
                val tenantWide = grants.any { it.scopeUnit == null }
                MyPermissionResponse(
                    permission.key,
                    tenantWide,
                    if (tenantWide) emptyList() else grants.mapNotNull { it.scopeUnit }.distinct(),
                )
            }
        val rank = policy.clearanceRank()
        val name = transactions.readInTenant(TenantContext.current()) { levels.byRank(rank)?.name }.orEmpty()
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(MyAccessResponse(permissions.sortedBy { it.key }, MyClearanceResponse(rank, name)))
    }
}
