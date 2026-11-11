package com.memo.memo.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.Clearances
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.tenancy.TenantCreated
import com.memo.tenancy.TenantRegistry
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Memo types (design E01 §5): every signed-in user lists the active ones for the create form; `memo.types.manage` holders
 * create and edit them, audited. A type is never deleted, only deactivated. Inside a tenant transaction.
 */
@Service
class MemoTypes(
    private val store: MemoTypeStore,
    private val clearances: Clearances,
    private val policy: Policy,
    private val audit: AuditLog,
) {
    fun active(): List<MemoType> = store.all().filter { it.active }

    fun all(): List<MemoType> {
        policy.require(Permission.MEMO_TYPES_MANAGE)
        return store.all()
    }

    fun get(id: UUID): MemoType = store.find(id) ?: throw MemoNotFoundException("memo type")

    fun create(fields: MemoTypeFields): MemoType {
        policy.require(Permission.MEMO_TYPES_MANAGE)
        check(fields, null)
        val type = get(store.insert(fields))
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
        check(fields, id)
        refuseIf(before.active && !fields.active && active().none { it.id != id }, "LAST_ACTIVE") {
            "Keep at least one memo type active, or people cannot create memos."
        }
        if (!store.update(id, expectedVersion, fields)) throw ConcurrentModificationException("memo type $id", expectedVersion)
        val after = get(id)
        audit.record("memo.type.updated", OBJECT_TYPE, id.toString(), before.audited(), after.audited())
        return after
    }

    private fun check(
        fields: MemoTypeFields,
        id: UUID?,
    ) {
        val level = fields.defaultClassificationId
        val problems =
            listOfNotNull(
                FieldProblem("name", "duplicate", "Another memo type has this name.").takeIf { store.nameTaken(fields.name, id) },
                FieldProblem("defaultClassificationId", "unknown", "No such classification level.").takeIf {
                    level != null && clearances.level(level) == null
                },
            )
        if (problems.isNotEmpty()) throw MemoRejectedException(problems)
    }

    private fun MemoType.audited() =
        mapOf(
            "name" to name,
            "defaultClassificationId" to defaultClassificationId?.toString(),
            "stepUpOnApprove" to stepUpOnApprove,
            "active" to active,
        )

    companion object {
        const val OBJECT_TYPE = "memo.type"
    }
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
