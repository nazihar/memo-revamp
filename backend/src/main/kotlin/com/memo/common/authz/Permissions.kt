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

    /** The tenant's mail relay, test sends and the delivery log (E06). */
    NOTIFICATION_SETTINGS_MANAGE("notification.settings.manage", scopable = false, privileged = true),

    /** Security settings, language defaults, terminology and branding (E07). */
    TENANT_SETTINGS_MANAGE("tenant.settings.manage", scopable = false, privileged = true),

    /** Create and submit memos (Phase 2 E01); every active user holds it through the built-in user role. */
    MEMO_CREATE("memo.create", scopable = false, privileged = false),

    /** Memo types: draft and submit their versions (Phase 2 E01; maker of maker-checker since Phase 3 E01). */
    MEMO_TYPES_MANAGE("memo.types.manage", scopable = false, privileged = true),

    /** Publish or send back a memo-type version someone else submitted (Phase 3 E01; checker); with a step-up. */
    MEMO_TYPES_APPROVE("memo.types.approve", scopable = false, privileged = true),

    /** See open workflow tasks and reassign them, for example when an approver left (Phase 2 E01). */
    WORKFLOW_TASKS_REASSIGN("workflow.tasks.reassign", scopable = false, privileged = true),

    /** Author workflow definitions and submit them for review (Phase 2 E03; maker of maker-checker). */
    WORKFLOW_DEFINITIONS_MANAGE("workflow.definitions.manage", scopable = false, privileged = true),

    /** Review, publish and retire workflow definitions, and move running items to a new version (Phase 2 E03; checker). */
    WORKFLOW_DEFINITIONS_APPROVE("workflow.definitions.approve", scopable = false, privileged = true),

    /** Cancel a running workflow with a reason (Phase 2 E03). */
    WORKFLOW_INSTANCES_CANCEL("workflow.instances.cancel", scopable = false, privileged = true),

    /** Create and change the queues workflow steps can go to (Phase 2 E04). */
    WORKFLOW_QUEUES_MANAGE("workflow.queues.manage", scopable = false, privileged = true),

    /** Draft and submit versions of the delegation of authority (Phase 2 E04). */
    WORKFLOW_DOA_MANAGE("workflow.doa.manage", scopable = false, privileged = true),

    /** Publish or send back a DoA version someone else submitted (Phase 2 E04); with a step-up. */
    WORKFLOW_DOA_APPROVE("workflow.doa.approve", scopable = false, privileged = true),

    /** Set the business calendar SLAs count working time on (Phase 2 E05). */
    WORKFLOW_CALENDAR_MANAGE("workflow.calendar.manage", scopable = false, privileged = true),

    /**
     * Read the memos raised in a unit subtree (Phase 3 E03, ADR-0050), up to one's clearance; for example internal audit
     * over a division. Granted by tenants only: no built-in role holds it.
     */
    MEMO_READ_UNIT("memo.read.unit", scopable = true, privileged = true),

    /** Revoke any share of a memo one can read (Phase 3 E03, ADR-0050). Granted by tenants only. */
    MEMO_SHARE_MANAGE("memo.share.manage", scopable = false, privileged = true),

    /** Add copy recipients to an approved memo one can read, for example a registry clerk (Phase 3 E03, ADR-0050). */
    MEMO_COPY_ADD("memo.copy.add", scopable = false, privileged = true),

    /** Keep the distribution lists copy-to can name (Phase 3 E03, ADR-0050). */
    MEMO_DISTRIBUTION_MANAGE("memo.distribution.manage", scopable = false, privileged = true),

    /**
     * Make a new version of a memo's final record, with a step-up and a reason, or retry a failed one (Phase 3 E04,
     * ADR-0053). Granted by tenants only: no built-in role holds it.
     */
    MEMO_RENDITION_REGENERATE("memo.rendition.regenerate", scopable = false, privileged = true),

    /**
     * See the open tasks of the people seated in a unit and its sub-units, and reassign them (Phase 3 E05 team view, for
     * example a COO or a registry). Holders of a unit's head position have it for their subtree without a grant.
     */
    INBOX_TEAM_READ("inbox.team.read", scopable = true, privileged = true),

    /** The approvals dashboard of a unit and its sub-units (Phase 3 E05); heads have it for their subtree without a grant. */
    MEMO_DASHBOARD_READ("memo.dashboard.read", scopable = true, privileged = false),
    ;

    companion object {
        private val BY_KEY = entries.associateBy { it.key }

        /**
         * Permissions that open other people's memos (ADR-0050): a tenant grants them on purpose, through a role of its
         * own; the built-in administrator role does not carry them.
         */
        val GRANTED_ON_PURPOSE: Set<Permission> get() =
            setOf(
                MEMO_READ_UNIT,
                MEMO_SHARE_MANAGE,
                MEMO_COPY_ADD,
                MEMO_RENDITION_REGENERATE,
                INBOX_TEAM_READ,
                MEMO_DASHBOARD_READ,
            )

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
    TENANT_ADMIN("tenant_admin", "Organisation administrator", Permission.entries.toSet() - Permission.GRANTED_ON_PURPOSE),
    ORG_ADMIN("org_admin", "Org directory administrator", setOf(Permission.DIRECTORY_READ, Permission.ORG_MANAGE)),
    AUDITOR("auditor", "Auditor", setOf(Permission.DIRECTORY_READ, Permission.AUDIT_READ)),
    USER("user", "User", setOf(Permission.DIRECTORY_READ, Permission.MEMO_CREATE)),
    ;

    companion object {
        private val BY_KEY = entries.associateBy { it.key }

        fun ofKey(key: String): BuiltInRole? = BY_KEY[key]
    }
}
