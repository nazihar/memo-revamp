package com.memo.memo.internal

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import com.memo.audit.AuditLog
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.jobs.ActiveTenants
import com.memo.common.jobs.Jobs
import com.memo.common.jobs.TenantScopedPayload
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.refuseIf
import com.memo.jooq.tables.references.MEMO_TYPE_VERSION
import org.jooq.impl.DSL
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import com.memo.jooq.tables.references.MEMO_ as MEMO

/** Closing approved memos (design E01 §1). Every method runs inside a tenant transaction. */
@Repository
class MemoClosingStore(
    private val tenantDsl: TenantDsl,
) {
    /** APPROVED → CLOSED, guarded on the status and, when given, [expectedVersion]; false when the memo moved on. */
    fun close(
        id: UUID,
        expectedVersion: Long?,
        now: OffsetDateTime,
    ): Boolean =
        tenantDsl
            .current()
            .update(MEMO)
            .set(MEMO.STATUS, MemoStatus.CLOSED.name)
            .set(MEMO.CLOSED_AT, now)
            .set(MEMO.VERSION, MEMO.VERSION.plus(1))
            .set(MEMO.UPDATED_AT, now)
            .where(
                MEMO.ID.eq(id),
                MEMO.STATUS.eq(MemoStatus.APPROVED.name),
                expectedVersion?.let { MEMO.VERSION.eq(it) } ?: DSL.noCondition(),
            ).execute() == 1

    /** Approved memos whose pinned version closes them after its days, approved before then, at most [limit]. */
    fun due(
        now: OffsetDateTime,
        limit: Int,
    ): List<UUID> =
        tenantDsl
            .current()
            .select(MEMO.ID)
            .from(MEMO)
            .join(MEMO_TYPE_VERSION)
            .on(MEMO_TYPE_VERSION.TENANT_ID.eq(MEMO.TENANT_ID), MEMO_TYPE_VERSION.ID.eq(MEMO.MEMO_TYPE_VERSION_ID))
            .where(
                MEMO.STATUS.eq(MemoStatus.APPROVED.name),
                MEMO_TYPE_VERSION.AUTO_CLOSE_DAYS.isNotNull,
                MEMO.APPROVED_AT.lt(
                    DSL.field(
                        "{0} - make_interval(days => {1})",
                        OffsetDateTime::class.java,
                        DSL.value(now),
                        MEMO_TYPE_VERSION.AUTO_CLOSE_DAYS,
                    ),
                ),
            ).orderBy(MEMO.APPROVED_AT)
            .limit(limit)
            .fetch(MEMO.ID)
            .filterNotNull()
}

/**
 * Closes approved memos (design E01 §1): by their initiator, or by the system once the days their pinned type version sets
 * have passed since approval. A closed memo is read-only for good and is never reopened. Inside a tenant transaction.
 */
@Component
class MemoClosing(
    private val memos: Memos,
    private val store: MemoClosingStore,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    fun close(
        id: UUID,
        remark: String?,
    ): Memo {
        val memo = memos.own(id)
        refuseIf(memo.status != MemoStatus.APPROVED, "NOT_APPROVED") { "Only an approved memo can be closed." }
        if (!store.close(id, memo.version, now())) throw ConcurrentModificationException("memo $id", memo.version)
        audit.record(
            "memo.closed",
            Memos.OBJECT_TYPE,
            id.toString(),
            after =
                mapOf(
                    "by" to "initiator",
                    "remark" to (remark?.trim()?.isNotEmpty() == true),
                ),
        )
        return memos.get(id)
    }

    /** Closes the memos due now, each guarded on its status; returns how many closed. Safe to run again. */
    fun closeDue(): Int {
        val now = now()
        return store.due(now, BATCH).count { id ->
            store.close(id, null, now).also { closed ->
                if (closed) audit.record("memo.closed", Memos.OBJECT_TYPE, id.toString(), after = mapOf("by" to "auto"))
            }
        }
    }

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private companion object {
        const val BATCH = 500
    }
}

/** Task data of the per-tenant auto-close task. */
data class MemoCloseTenantTask(
    override val tenantId: UUID,
) : TenantScopedPayload

/** The auto-close tasks (ADR-0018): an hourly platform sweep fans out to one task per active tenant, run as the system. */
@Configuration(proxyBeanMethods = false)
class MemoClosingJobs {
    @Bean
    fun memoAutoCloseTask(
        jobs: Jobs,
        closing: MemoClosing,
        transactions: TenantTransactions,
    ): OneTimeTask<MemoCloseTenantTask> =
        jobs.tenantTask(AUTO_CLOSE, MemoCloseTenantTask::class.java) {
            transactions.inTenant(TenantContext.current()) { closing.closeDue() }
        }

    @Bean
    fun memoAutoCloseSweep(
        jobs: Jobs,
        tenants: ActiveTenants,
        scheduler: ObjectProvider<SchedulerClient>,
        task: ObjectProvider<OneTimeTask<MemoCloseTenantTask>>,
    ): RecurringTask<Void> =
        jobs.platformTask("memo-auto-close-sweep", Schedules.fixedDelay(EVERY)) {
            // Instance id = tenant id, so overlapping sweeps collapse.
            val client = scheduler.getObject()
            val close = task.getObject()
            tenants.ids().forEach { tenant ->
                client.scheduleIfNotExists(close.schedulableInstance(tenant.toString(), MemoCloseTenantTask(tenant.value)))
            }
        }

    private companion object {
        const val AUTO_CLOSE = "memo-auto-close"
        val EVERY: Duration = Duration.ofHours(1)
    }
}
