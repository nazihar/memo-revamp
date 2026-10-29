package com.memo.common.authz

/**
 * The fixed permission catalogue (ADR-0027). Tenants combine permissions into roles; what a permission allows is defined
 * here, in code, so it means the same in every tenant. [scopable] permissions can be granted for a unit subtree only;
 * holders of a [privileged] permission count as administrators for the MFA policy (ADR-0024).
 */
enum class Permission(
    val key: String,
    val scopable: Boolean,
    val privileged: Boolean,
) {
    /** View the org directory and use the people picker (E04). */
    DIRECTORY_READ("directory.read", scopable = false, privileged = false),

    /** Units, positions, assignments and reporting lines (E04). */
    ORG_MANAGE("org.manage", scopable = true, privileged = true),

    /** Legal entities (E04). */
    ORG_LEGAL_ENTITIES_MANAGE("org.legal_entities.manage", scopable = false, privileged = true),

    /** Invite, deactivate, reactivate, unlock, reset MFA, revoke sessions (E03 endpoints). */
    IDENTITY_USERS_MANAGE("identity.users.manage", scopable = false, privileged = true),

    /** Custom roles and role grants (E04). */
    AUTHZ_ROLES_MANAGE("authz.roles.manage", scopable = false, privileged = true),

    /** Classification levels and user clearances (E04). */
    AUTHZ_CLASSIFICATIONS_MANAGE("authz.classifications.manage", scopable = false, privileged = true),

    /** The audit history of org and authz objects (E04; the full audit viewer in E07). */
    AUDIT_READ("audit.read", scopable = false, privileged = true),
    ;

    companion object {
        private val BY_KEY = entries.associateBy { it.key }

        fun ofKey(key: String): Permission? = BY_KEY[key]
    }
}

/**
 * Roles every tenant has (ADR-0027). Their permissions come from code and cannot be edited; every active user holds
 * [USER] implicitly, without a grant.
 */
enum class BuiltInRole(
    val key: String,
    val displayName: String,
    val permissions: Set<Permission>,
) {
    TENANT_ADMIN("tenant_admin", "Organisation administrator", Permission.entries.toSet()),
    ORG_ADMIN("org_admin", "Org directory administrator", setOf(Permission.DIRECTORY_READ, Permission.ORG_MANAGE)),
    AUDITOR("auditor", "Auditor", setOf(Permission.DIRECTORY_READ, Permission.AUDIT_READ)),
    USER("user", "User", setOf(Permission.DIRECTORY_READ)),
    ;

    companion object {
        private val BY_KEY = entries.associateBy { it.key }

        fun ofKey(key: String): BuiltInRole? = BY_KEY[key]
    }
}
