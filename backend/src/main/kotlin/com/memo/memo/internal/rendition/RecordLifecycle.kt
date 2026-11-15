package com.memo.memo.internal.rendition

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.memo.audit.AuditLog
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.jobs.TenantScopedPayload
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.files.RecordRetentions
import com.memo.memo.internal.MemoRejectedException
import com.memo.memo.internal.MemoStatus
import com.memo.memo.internal.MemoStore
import com.memo.memo.internal.MemoTypes
import com.memo.memo.internal.Memos
import com.memo.memo.internal.RetentionFrom
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.time.ZoneOffset
import java.util.UUID

/** Task data of a record's retention extension after its memo closed. */
data class RecordRetentionTask(
    override val tenantId: UUID,
    val memoId: UUID,
) : TenantScopedPayload

/**
 * What happens to a final record after it is made (ADR-0053, design E04 §6): a new version on purpose (permission, the
 * caller's step-up, a reason), another try of a failed one, and its lock extended to closure plus the type's years when
 * the memo closes. Inside a tenant transaction, except [extendRetention], which talks to storage.
 */
@Service
class RecordLifecycle(
    private val memos: Memos,
    private val memoStore: MemoStore,
    private val types: MemoTypes,
    private val store: MemoRenditionStore,
    private val renditions: MemoRenditions,
    private val policy: Policy,
    private val retentions: RecordRetentions,
    private val audit: AuditLog,
    private val tenantDsl: TenantDsl,
    private val transactions: TenantTransactions,
    private val scheduler: ObjectProvider<SchedulerClient>,
    @param:Qualifier("memoRenditionRetentionTask") private val task: ObjectProvider<OneTimeTask<RecordRetentionTask>>,
) {
    /** A new version of [memoId]'s record; the current one stays as it is. */
    fun regenerate(
        memoId: UUID,
        reason: String,
    ): MemoRendition {
        policy.require(Permission.MEMO_RENDITION_REGENERATE)
        if (reason.trim().length !in 1..MAX_REASON) {
            throw MemoRejectedException(
                listOf(FieldProblem("reason", "REQUIRED", "Say why a new version is needed (at most $MAX_REASON characters).")),
            )
        }
        val memo = memos.get(memoId)
        refuseIf(memo.status != MemoStatus.APPROVED && memo.status != MemoStatus.CLOSED, "WRONG_STATE") {
            "Only an approved or closed memo has a final record."
        }
        val versions = store.of(memo.id)
        refuseIf(versions.any { it.state == RenditionState.PENDING || it.state == RenditionState.BUILDING }, "RENDITION_BUSY") {
            "A version of the final record is being made."
        }
        val actor = (ActorContext.current().actor as? Actor.User)?.userId
        return renditions.request(memo.id, RenditionTrigger.REGENERATE, reason.trim(), actor)
    }

    /** The failed newest version, tried again from the start. */
    fun retry(memoId: UUID): MemoRendition {
        policy.require(Permission.MEMO_RENDITION_REGENERATE)
        val memo = memos.get(memoId)
        val failed = store.of(memo.id).firstOrNull()?.takeIf { it.state == RenditionState.FAILED }
        refuseIf(failed == null || !store.reopen(failed.id), "WRONG_STATE") { "Only a failed final record can be tried again." }
        val reopened = checkNotNull(store.get(checkNotNull(failed).id))
        audit.record(
            "memo.rendition.requested",
            Memos.OBJECT_TYPE,
            memo.id.toString(),
            after = mapOf("version" to reopened.version, "trigger" to RenditionTrigger.RETRY.name),
        )
        renditions.schedule(reopened)
        return reopened
    }

    /** The memo closed: its record's lock is extended by a task, outside the closing transaction. */
    fun closed(memoId: UUID) {
        val client = scheduler.ifAvailable?.takeIf { renditions.jobs }
        val extend = task.ifAvailable
        if (client != null && extend != null) {
            client.scheduleIfNotExists(extend.schedulableInstance("$memoId", RecordRetentionTask(tenantDsl.tenant().value, memoId)))
        }
    }

    /**
     * Every made version of [memoId]'s record kept until its closure plus the type's years, when the type counts them
     * from closure; returns how many versions were extended. Outside a transaction (storage is called); safe to repeat.
     */
    fun extendRetention(memoId: UUID): Int {
        val tenant = TenantContext.current()
        val (until, versions) =
            transactions.readInTenant(tenant) {
                val memo = memoStore.find(memoId)
                val retention = memo?.let { types.versionFor(it)?.settings?.retention }
                val closedAt = memo?.closedAt
                val until =
                    closedAt?.takeIf { retention?.from == RetentionFrom.CLOSURE }?.let {
                        it.atOffset(ZoneOffset.UTC).plusYears(checkNotNull(retention).years.toLong()).toInstant()
                    }
                until to store.of(memoId).filter { it.state == RenditionState.READY }.mapNotNull { it.fileVersionId }
            }
        val extended = if (until == null) 0 else versions.count { retentions.extend(it, until) }
        if (extended > 0) {
            transactions.inTenant(tenant) {
                audit.record(
                    "memo.rendition.retention_extended",
                    Memos.OBJECT_TYPE,
                    memoId.toString(),
                    after = mapOf("versions" to extended, "until" to until.toString()),
                )
            }
        }
        return extended
    }

    private companion object {
        const val MAX_REASON = 500
    }
}
