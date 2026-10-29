package com.memo.identity.admin

import com.memo.audit.AuditLog
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.FieldProblem
import com.memo.common.web.PreconditionRequiredException
import com.memo.common.web.ProblemTypes
import com.memo.identity.DuplicateEmailException
import com.memo.identity.Invitations
import com.memo.identity.LifecycleRefusedException
import com.memo.identity.StepUp
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.identity.UserDirectory
import com.memo.identity.UserId
import com.memo.identity.UserLifecycle
import com.memo.identity.UserListItem
import com.memo.identity.UserStatus
import com.memo.identity.mfa.MfaService
import com.memo.identity.session.SessionPrincipal
import com.memo.identity.session.SessionRevocation
import com.memo.system.Idempotency
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Instant
import java.util.UUID

@Schema(name = "AdminUser")
data class AdminUserResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val email: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val displayName: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val status: UserStatus,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val tenantAdmin: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val mfaEnrolled: Boolean,
    val lastLoginAt: Instant? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Send as If-Match on lifecycle actions")
    val version: Long,
)

@Schema(name = "AdminUserPage")
data class AdminUserPageResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val items: List<AdminUserResponse>,
    @field:Schema(description = "Pass as cursor for the next page; absent at the end")
    val nextCursor: String? = null,
)

@Schema(name = "InviteUserRequest")
data class InviteUserRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 320)
    val email: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200)
    val displayName: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val tenantAdmin: Boolean = false,
)

/**
 * User administration for tenant admins (design E03 §3, §4). Every mutation needs a recent step-up; lifecycle actions
 * need `If-Match: "<version>"`; inviting needs an `Idempotency-Key`.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@Tag(name = "admin")
class AdminUsersController(
    private val directory: UserDirectory,
    private val lifecycle: UserLifecycle,
    private val invitations: Invitations,
    private val accounts: UserAccounts,
    private val mfa: MfaService,
    private val sessions: SessionRevocation,
    private val stepUp: StepUp,
    private val idempotency: Idempotency,
    private val transactions: TenantTransactions,
    private val audit: AuditLog,
    private val json: JsonMapper,
) {
    @GetMapping
    @Operation(operationId = "listUsers", summary = "List the organisation's users", description = "Sorted by name; keyset-paginated.")
    fun list(
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) status: UserStatus?,
        @Parameter(description = "Prefix of name or email") @RequestParam(required = false) q: String?,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
    ): ResponseEntity<AdminUserPageResponse> {
        val page = transactions.readInTenant(tenant()) { directory.page(cursor, status, q?.take(MAX_QUERY), limit) }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            AdminUserPageResponse(
                page.items.map {
                    it.toResponse()
                },
                page.nextCursor,
            ),
        )
    }

    @PostMapping
    @Operation(
        operationId = "inviteUser",
        summary = "Invite a user",
        description =
            "Creates an INVITED account and emails a one-time link (7 days). " +
                "A repeat with the same Idempotency-Key returns the same user.",
        responses = [
            ApiResponse(
                responseCode = "201",
                description = "Invited",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = AdminUserResponse::class))],
            ),
            ApiResponse(
                responseCode = "409",
                description = "An account with this email exists",
                content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ProblemDetail::class))],
            ),
        ],
    )
    fun invite(
        @AuthenticationPrincipal principal: SessionPrincipal,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody body: InviteUserRequest,
    ): ResponseEntity<Any> {
        stepUp.require()
        val created =
            transactions.inTenant(tenant()) {
                val inviter = requireNotNull(accounts.find(UserId(principal.userId))).displayName
                val id =
                    idempotency.once("identity.user.invite", idempotencyKey, json.writeValueAsString(body)) {
                        invitations
                            .invite(
                                body.email.take(MAX_EMAIL),
                                body.displayName.trim().take(MAX_NAME),
                                body.tenantAdmin,
                                inviter,
                            ).id
                            .toString()
                    }
                item(UserId(UUID.fromString(id)))
            }
        return ResponseEntity.created(URI.create("/api/v1/admin/users/${created.id}")).eTag(etag(created.version)).body(created)
    }

    @PostMapping("/{id}/deactivate")
    @Operation(
        operationId = "deactivateUser",
        summary = "Deactivate a user",
        description = "Signs the user out everywhere. Not yourself, not the last admin.",
    )
    fun deactivate(
        @AuthenticationPrincipal principal: SessionPrincipal,
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<AdminUserResponse> =
        act(id, ifMatch, revokeSessions = true) { version -> lifecycle.deactivate(UserId(id), version, UserId(principal.userId)) }

    @PostMapping("/{id}/reactivate")
    @Operation(operationId = "reactivateUser", summary = "Reactivate a user")
    fun reactivate(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<AdminUserResponse> = act(id, ifMatch) { version -> lifecycle.reactivate(UserId(id), version) }

    @PostMapping("/{id}/unlock")
    @Operation(operationId = "unlockUser", summary = "Lift a temporary lock")
    fun unlock(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<AdminUserResponse> = act(id, ifMatch) { version -> lifecycle.unlock(UserId(id), version) }

    @PostMapping("/{id}/mfa-reset")
    @Operation(
        operationId = "resetUserMfa",
        summary = "Reset two-step sign-in",
        description = "The user sets up an authenticator again at the next sign-in; signs them out.",
    )
    fun resetMfa(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<AdminUserResponse> = act(id, ifMatch, revokeSessions = true) { version -> lifecycle.resetMfa(UserId(id), version) }

    @PostMapping("/{id}/sessions/revoke")
    @Operation(
        operationId = "revokeUserSessions",
        summary = "Sign a user out everywhere",
        responses = [ApiResponse(responseCode = "204", description = "Signed out", content = [Content()])],
    )
    fun revokeSessions(
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        stepUp.require()
        val exists = transactions.inTenant(tenant()) { accounts.find(UserId(id)) != null }
        if (!exists) return ResponseEntity.notFound().build()
        val revoked = sessions.revokeAll("${tenant()}:$id")
        transactions.inTenant(tenant()) {
            audit.record(
                "identity.session.revoked",
                "identity.user",
                id.toString(),
                after =
                    mapOf(
                        "reason" to "admin",
                        "sessions" to revoked,
                    ),
            )
        }
        return ResponseEntity.noContent().build()
    }

    @ExceptionHandler(LifecycleRefusedException::class)
    fun refused(ex: LifecycleRefusedException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ProblemTypes
                .of(
                    HttpStatus.CONFLICT,
                    ProblemTypes.CONFLICT,
                    ex.message ?: "Not possible.",
                ).apply { setProperty("reason", ex.reason) },
        )

    @ExceptionHandler(DuplicateEmailException::class)
    fun duplicate(): ResponseEntity<ProblemDetail> =
        ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(
                ProblemTypes.validation(listOf(FieldProblem("email", "TAKEN", "An account with this email already exists."))).apply {
                    status =
                        HttpStatus.CONFLICT.value()
                },
            )

    /** Step-up, If-Match, the change, and (for deactivation and MFA reset) signing the user out everywhere. */
    private fun act(
        id: UUID,
        ifMatch: String?,
        revokeSessions: Boolean = false,
        change: (Long) -> UserAccount,
    ): ResponseEntity<AdminUserResponse> {
        stepUp.require()
        val version = parseIfMatch(ifMatch)
        val updated = transactions.inTenant(tenant()) { change(version).let { item(it.id) } }
        if (revokeSessions) sessions.revokeAll("${tenant()}:$id")
        return ResponseEntity.ok().eTag(etag(updated.version)).body(updated)
    }

    private fun item(id: UserId): AdminUserResponse {
        val account = requireNotNull(accounts.find(id)) { "unknown user" }
        return AdminUserResponse(
            account.id.value,
            account.email,
            account.displayName,
            account.status,
            account.tenantAdmin,
            mfa.isEnrolled(account.id),
            account.lastLoginAt,
            account.version,
        )
    }

    private companion object {
        const val MAX_QUERY = 100
        const val MAX_EMAIL = 320
        const val MAX_NAME = 200
    }
}

private fun UserListItem.toResponse() = AdminUserResponse(id, email, displayName, status, tenantAdmin, mfaEnrolled, lastLoginAt, version)

/** `If-Match: "<version>"` (a weak `W/` prefix is accepted); missing or unreadable is 428. */
private fun parseIfMatch(header: String?): Long =
    header
        ?.removePrefix("W/")
        ?.trim('"', ' ')
        ?.toLongOrNull() ?: throw PreconditionRequiredException()

private fun etag(version: Long) = "\"$version\""

private fun tenant() = TenantContext.current()
