package com.memo.common.authz

import java.util.UUID

/**
 * How a handler may be reached (ADR-0027). Every controller method, or its class, carries exactly one of these; the
 * authz module's interceptor rejects handlers with none (deny by default), and the endpoint-coverage meta-test fails
 * the build for them.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class PublicEndpoint

/**
 * Part of signing in or out (password, MFA, enrolment, logout): Spring Security's URL rules decide who may call it,
 * including partial sessions that have not proven every factor yet.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class AuthenticationFlow

/** Any signed-in user with a complete session (Spring Security already demands one outside the public endpoints). */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class SignedIn

/**
 * A platform operator with a complete operator session, on the operator console's host (ADR-0031). Spring Security's
 * platform chain demands the session; the interceptor checks the principal is an operator.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class OperatorOnly

/**
 * Needs [value] somewhere: tenant-wide for unscoped permissions, for some unit for scoped ones. The service then checks
 * the object's own unit with [Policy.require].
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresPermission(
    val value: Permission,
)

/**
 * Role grants that other modules need to make or ask about (ADR-0027), implemented by the authz module so that identity
 * and the demo seeder do not depend on it. Calls run inside a tenant transaction.
 */
interface AccessAdministration {
    /**
     * Grants [role] to [user] unless they already hold it: tenant-wide, or for the subtree of [scopeUnit], where the grant
     * confers only the role's scopable permissions.
     */
    fun grantBuiltIn(
        user: UUID,
        role: BuiltInRole,
        scopeUnit: UUID? = null,
    )

    /** Which of [users] hold a tenant-wide [BuiltInRole.TENANT_ADMIN] grant. */
    fun tenantAdmins(users: Collection<UUID>): Set<UUID>

    /**
     * Holders of a tenant-wide tenant admin grant other than [except], with the role row locked until commit, so two
     * concurrent "last admin" checks cannot both pass (threat model E04 E3).
     */
    fun otherTenantAdminsLocked(except: UUID): Set<UUID>
}

/**
 * The org tree as the policy needs it (ADR-0026), implemented by the org module. Without an implementation (before E04
 * T3), unit-scoped grants match nothing.
 */
interface OrgTree {
    /** Whether [unit] is [root] or lies below it. */
    fun isWithin(
        unit: UUID,
        root: UUID,
    ): Boolean

    /** [root] and every unit below it. */
    fun subtree(root: UUID): Set<UUID>
}

/** Cache names that other modules evict (ADR-0020). */
object AccessCaches {
    /** Effective grants and clearance per user. */
    const val GRANTS = "authz.grants"
}

/**
 * A user's grants changed (ids only, ADR-0017); [gained] is true when a grant was added. Published inside the changing
 * transaction; identity listens to sign out a user who became privileged without MFA (ADR-0024).
 */
data class GrantsChanged(
    override val tenantId: UUID,
    val userId: UUID,
    val gained: Boolean,
) : com.memo.common.events.TenantScopedEvent

/**
 * Step-up authentication (ADR-0024) for modules that cannot depend on `identity` (it depends on them): a fresh second
 * factor within the tenant's window, else [com.memo.common.web.StepUpRequiredException]. Implemented by `identity`.
 */
interface StepUpCheck {
    fun require()
}
