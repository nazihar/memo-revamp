package com.memo.memo.internal

import com.memo.audit.AuditLog
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.jooq.tables.references.MEMO_INTEREST
import com.memo.workflow.WorkflowHandOns
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Declared interests in memos (design E04 §3); the reason stays in this table, never in the audit log. In a tenant transaction. */
@Repository
class MemoInterestStore(
    private val tenantDsl: TenantDsl,
    private val clock: Clock,
) {
    fun of(memo: UUID): Set<UUID> =
        tenantDsl
            .current()
            .select(MEMO_INTEREST.USER_ID)
            .from(MEMO_INTEREST)
            .where(MEMO_INTEREST.MEMO_ID.eq(memo))
            .fetchSet(MEMO_INTEREST.USER_ID)
            .filterNotNull()
            .toSet()

    /** False when [user] had already declared an interest. */
    fun add(
        memo: UUID,
        user: UUID,
        declaredBy: UUID,
        reason: String,
    ): Boolean =
        tenantDsl
            .current()
            .insertInto(MEMO_INTEREST)
            .set(MEMO_INTEREST.TENANT_ID, tenantDsl.tenant().value)
            .set(MEMO_INTEREST.MEMO_ID, memo)
            .set(MEMO_INTEREST.USER_ID, user)
            .set(MEMO_INTEREST.DECLARED_BY, declaredBy)
            .set(MEMO_INTEREST.REASON, reason)
            .set(MEMO_INTEREST.CREATED_AT, OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute() == 1

    fun remove(
        memo: UUID,
        user: UUID,
    ): Boolean =
        tenantDsl
            .current()
            .deleteFrom(MEMO_INTEREST)
            .where(MEMO_INTEREST.MEMO_ID.eq(memo), MEMO_INTEREST.USER_ID.eq(user))
            .execute() == 1
}

/**
 * Who has an interest in a memo (SOD-4, design E04 §4.4): the initiator lists them while the memo is theirs to edit
 * (a beneficiary, a related party), and an approver declares their own on their task and stands aside. People with an
 * interest are never asked to decide on the memo. Audited with ids only. In a tenant transaction.
 */
@Service
class MemoInterests(
    private val memos: Memos,
    private val store: MemoInterestStore,
    private val tasks: MemoTasks,
    private val handOns: WorkflowHandOns,
    private val audit: AuditLog,
) {
    fun declare(
        memoId: UUID,
        user: UUID,
        reason: String?,
    ): Memo {
        val memo = memos.own(memoId)
        refuseIf(!memo.status.editable, "WRONG_STATE") { "Interests can be changed while the memo is a draft or returned to you." }
        val text = reason?.trim()?.ifEmpty { null } ?: throw MemoRejectedException(listOf(REASON_REQUIRED))
        if (store.add(memo.id, user, actor(), text)) record("memo.interest.declared", memo.id, user)
        return memos.get(memo.id)
    }

    fun remove(
        memoId: UUID,
        user: UUID,
    ): Memo {
        val memo = memos.own(memoId)
        refuseIf(!memo.status.editable, "WRONG_STATE") { "Interests can be changed while the memo is a draft or returned to you." }
        if (store.remove(memo.id, user)) record("memo.interest.removed", memo.id, user)
        return memos.get(memo.id)
    }

    /** The task's holder declares their own interest and stands aside; the step goes to someone else. */
    fun recuse(
        taskId: UUID,
        reason: String?,
    ): Memo {
        val memo = tasks.memoOf(taskId)
        val text = reason?.trim()?.ifEmpty { null } ?: throw MemoRejectedException(listOf(REASON_REQUIRED))
        val user = actor()
        if (store.add(memo.id, user, user, text)) record("memo.interest.declared", memo.id, user)
        handOns.recuse(taskId, text)
        return memos.get(memo.id)
    }

    private fun record(
        action: String,
        memo: UUID,
        user: UUID,
    ) = audit.record(action, Memos.OBJECT_TYPE, memo.toString(), after = mapOf("userId" to user.toString()))

    private fun actor(): UUID = checkNotNull((ActorContext.current().actor as? Actor.User)?.userId) { "interests need a signed-in user" }

    private companion object {
        val REASON_REQUIRED = FieldProblem("reason", "required", "Say what the interest is.")
    }
}
