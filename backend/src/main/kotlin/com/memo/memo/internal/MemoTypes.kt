package com.memo.memo.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.Clearances
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.review.ReviewState
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.tenancy.TenantCreated
import com.memo.tenancy.TenantRegistry
import com.memo.workflow.WorkflowDefinitionUsage
import com.memo.workflow.WorkflowDefinitions
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Memo types (design E01 §5): every signed-in user lists the active, published ones for the create form;
 * `memo.types.manage` holders create and edit them, audited. A type is never deleted, only deactivated. Inside a tenant
 * transaction.
 *
 * Since Phase 3 E01 a type changes through reviewed versions ([MemoTypeLifecycle], ADR-0043). [create] and [update] are
 * the Phase 2 direct path, kept for the current admin screen until E01 T6 replaces it: each direct change is recorded as
 * a new published version, so the version history stays complete.
 */
@Service
class MemoTypes(
    private val store: MemoTypeStore,
    private val versions: MemoTypeVersionStore,
    private val checks: MemoTypeChecks,
    private val policy: Policy,
    private val audit: AuditLog,
) {
    fun active(): List<MemoType> = store.all().filter { it.active && it.publishedVersion != null }

    fun all(): List<MemoType> {
        if (!policy.allows(Permission.MEMO_TYPES_APPROVE)) policy.require(Permission.MEMO_TYPES_MANAGE)
        return store.all()
    }

    fun get(id: UUID): MemoType = store.find(id) ?: throw MemoNotFoundException("memo type")

    fun create(fields: MemoTypeFields): MemoType {
        policy.require(Permission.MEMO_TYPES_MANAGE)
        val code = fields.code ?: store.freeCode(fields.name)
        checks.require(fields.settings(code), null)
        val type = get(store.insert(fields.copy(code = code), createdBy = actorOrNull()))
        audit.record("memo.type.created", OBJECT_TYPE, type.id.toString(), after = type.audited())
        return type
    }

    fun update(
        id: UUID,
        expectedVersion: Long,
        fields: MemoTypeFields,
    ): MemoType {
        policy.require(Permission.MEMO_TYPES_MANAGE)
        val before = get(id)
        val settings = fields.settings(fields.code ?: before.code)
        checks.require(settings, id)
        refuseIf(before.active && !fields.active && active().none { it.id != id }, "LAST_ACTIVE") {
            "Keep at least one memo type active, or people cannot create memos."
        }
        if (before.version != expectedVersion) throw ConcurrentModificationException("memo type $id", expectedVersion)
        val current = versions.published(id)
        val published =
            if (current != null && current.settings == settings) {
                current.number
            } else {
                versions.retirePublished(id)
                versions.insert(id, ReviewState.PUBLISHED, settings, actorOrNull())
            }
        if (!store.update(id, expectedVersion, fields.copy(code = settings.code), published)) {
            throw ConcurrentModificationException("memo type $id", expectedVersion)
        }
        val after = get(id)
        audit.record("memo.type.updated", OBJECT_TYPE, id.toString(), before.audited(), after.audited())
        return after
    }

    private fun MemoType.audited() =
        MemoTypeChecks.audited(
            MemoTypeSettings(name, code, defaultClassificationId, definitionKey, routePolicy, allowedApprovers, doaCheck, stepUpOnApprove),
        ) + mapOf("active" to active, "publishedVersion" to publishedVersion)

    companion object {
        const val OBJECT_TYPE = "memo.type"
    }
}

/**
 * What a memo type's settings must satisfy (design E01 §3, E03 §4.7, E04 §4.7): a unique name and code, a known
 * classification, a published workflow definition that fits the route policy, and bounded approver lists. Checked when a
 * draft is saved and submitted, and again when it is published, as the definitions may have changed meanwhile.
 */
@Component
class MemoTypeChecks(
    private val store: MemoTypeStore,
    private val clearances: Clearances,
    private val definitions: WorkflowDefinitions,
) {
    /** Throws [MemoRejectedException] listing what is wrong with [settings] for type [id] (null: a new type). */
    fun require(
        settings: MemoTypeSettings,
        id: UUID?,
    ) {
        val problems = problems(settings, id)
        if (problems.isNotEmpty()) throw MemoRejectedException(problems)
    }

    fun problems(
        settings: MemoTypeSettings,
        id: UUID?,
    ): List<FieldProblem> {
        val level = settings.defaultClassificationId
        val definition = definitions.choice(settings.definitionKey)
        val allowed = settings.allowedApprovers
        return listOfNotNull(
            FieldProblem("name", "LENGTH", "A name of 1 to $MAX_NAME characters.").takeIf {
                settings.name.isBlank() || settings.name.length > MAX_NAME || settings.name != settings.name.trim()
            },
            FieldProblem("name", "duplicate", "Another memo type has this name.").takeIf { store.nameTaken(settings.name, id) },
            FieldProblem("code", "FORMAT", "1 to 10 capital letters, digits or hyphens, starting with a letter or digit.").takeUnless {
                MemoTypeCodes.PATTERN.matches(settings.code)
            },
            FieldProblem("code", "duplicate", "Another memo type has this code.").takeIf { store.codeTaken(settings.code, id) },
            FieldProblem("defaultClassificationId", "unknown", "No such classification level.").takeIf {
                level != null && clearances.level(level) == null
            },
            FieldProblem("definitionKey", "unknown", "No published workflow definition has this key.").takeIf { definition == null },
            FieldProblem("routePolicy", "mismatch", MISMATCH.getValue(settings.routePolicy)).takeIf {
                definition != null && definition.routed != settings.routePolicy.routed
            },
            FieldProblem("allowedApprovers", "TOO_MANY", "At most $MAX_ALLOWED people, positions and roles each.").takeIf {
                listOf(allowed.people, allowed.positions, allowed.roles).any { it.size > MAX_ALLOWED }
            },
        )
    }

    companion object {
        const val MAX_NAME = 200
        const val MAX_ALLOWED = 200
        private val MISMATCH =
            mapOf(
                RoutePolicy.INITIATOR_DEFINED to "This definition has no route step for the initiator's approvers.",
                RoutePolicy.BOTH to "This definition has no route step for the initiator's approvers.",
                RoutePolicy.FIXED to "This definition takes the initiator's route; choose a policy with a route.",
            )

        /** The settings as the audit log records them (every setting; approver lists by count). */
        fun audited(settings: MemoTypeSettings): Map<String, Any?> =
            mapOf(
                "name" to settings.name,
                "code" to settings.code,
                "defaultClassificationId" to settings.defaultClassificationId?.toString(),
                "stepUpOnApprove" to settings.stepUpOnApprove,
                "definitionKey" to settings.definitionKey,
                "routePolicy" to settings.routePolicy.name,
                "allowedApprovers" to
                    mapOf(
                        "people" to settings.allowedApprovers.people.size,
                        "positions" to settings.allowedApprovers.positions.size,
                        "roles" to settings.allowedApprovers.roles.size,
                    ),
                "doaCheck" to settings.doaCheck,
            )
    }
}

/** The signed-in user acting, or null for the system (seeding, bootstrap). */
internal fun actorOrNull(): UUID? = (ActorContext.current().actor as? Actor.User)?.userId

/** A workflow definition chosen by a memo type, active or not, cannot be retired (design E03 §5). */
@Component
class MemoDefinitionUsage(
    private val store: MemoTypeStore,
) : WorkflowDefinitionUsage {
    override fun inUse(definitionKey: String): Boolean = store.all().any { it.definitionKey == definitionKey }
}

/** Gives every tenant the built-in "General memo" type: new tenants in their creating transaction, existing ones at start-up. */
@Component
class MemoBootstrap(
    private val types: MemoTypeStore,
    private val transactions: TenantTransactions,
) {
    @EventListener
    fun onTenantCreated(event: TenantCreated) = transactions.inTenant(TenantId(event.tenantId)) { types.ensureGeneral() }
}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class MemoBootstrapOnStartup(
    private val types: MemoTypeStore,
    private val registry: TenantRegistry,
    private val transactions: TenantTransactions,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        ActorContext.asSystem("memo-bootstrap") {
            registry.listActiveIds().forEach { tenant -> transactions.inTenant(tenant) { types.ensureGeneral() } }
        }
    }
}
