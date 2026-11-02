package com.memo.identity.admin

import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.etagOf
import com.memo.identity.UserAccounts
import com.memo.identity.UserId
import com.memo.identity.mfa.MfaService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** One user for the user detail screen (design E04 §13); the list and the lifecycle actions are in [AdminUsersController]. */
@RestController
@Tag(name = "admin")
@RequiresPermission(Permission.IDENTITY_USERS_MANAGE)
class AdminUserController(
    private val accounts: UserAccounts,
    private val mfa: MfaService,
    private val transactions: TenantTransactions,
) {
    @GetMapping("/api/v1/admin/users/{id}")
    @Operation(operationId = "getUser", summary = "One user of the organisation")
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<AdminUserResponse> {
        val user =
            transactions.readInTenant(TenantContext.current()) {
                accounts.find(UserId(id))?.let { account ->
                    AdminUserResponse(
                        account.id.value,
                        account.email,
                        account.displayName,
                        account.status,
                        accounts.isTenantAdmin(account.id),
                        mfa.isEnrolled(account.id),
                        account.lastLoginAt,
                        account.version,
                    )
                }
            }
        return if (user == null) {
            ResponseEntity.notFound().build()
        } else {
            ResponseEntity
                .ok()
                .eTag(etagOf(user.version))
                .cacheControl(CacheControl.noStore())
                .body(user)
        }
    }
}
