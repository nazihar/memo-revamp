package com.memo.memo.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.Clearances
import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.identity.UserStatus
import com.memo.identity.UserSummaries
import com.memo.jooq.tables.records.MemoCopyRecord
import com.memo.jooq.tables.references.MEMO_COPY
import com.memo.jooq.tables.references.MEMO_COPY_DELIVERY
import com.memo.jooq.tables.references.MEMO_RECEIPT
import com.memo.notification.NotificationKind
import com.memo.org.OrgDirectory
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import com.memo.jooq.tables.references.MEMO_ as MEMO

/** Who a copy goes to (ADR-0050): a person, a position (its holder when sent) or a distribution list. */
enum class RecipientKind { USER, POSITION, LIST }

/** When a copy goes: during review, for information, or once the memo is approved (the default). */
enum class SendWhen { AT_SUBMIT, AFTER_APPROVAL }

/** What became of a copy for one person. */
enum class DeliveryOutcome { SENT, NOT_CLEARED, INACTIVE }

data class MemoCopy(
    val id: UUID,
    val memoId: UUID,
    val kind: RecipientKind,
    val recipientId: UUID,
    val sendWhen: SendWhen,
    val addedBy: UUID,
    val addedAt: Instant,
    val sentAt: Instant?,
)

data class CopyDelivery(
    val copyId: UUID,
    val userId: UUID,
    val outcome: DeliveryOutcome,
    val at: Instant,
)

data class MemoReceipt(
    val userId: UUID,
    val openedAt: Instant,
    val documentVersion: Int?,
)

/** A recipient as the client names it. */
data class CopyRecipient(
    val kind: RecipientKind,
    val id: UUID,
    val sendWhen: SendWhen = SendWhen.AFTER_APPROVAL,
)

/** `memo_copy`, `memo_copy_delivery` and `memo_receipt` (V14); inside a tenant transaction. */
@Repository
class MemoCopyStore(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
) {
    /** The copies of [memoId] that stand (not removed), oldest first. */
    fun standing(memoId: UUID): List<MemoCopy> =
        tenantDsl
            .current()
            .selectFrom(MEMO_COPY)
            .where(MEMO_COPY.MEMO_ID.eq(memoId), MEMO_COPY.REMOVED_AT.isNull)
            .orderBy(MEMO_COPY.ADDED_AT, MEMO_COPY.ID)
            .fetch(::copy)

    fun insert(
        memoId: UUID,
        recipient: CopyRecipient,
        by: UUID,
        at: OffsetDateTime,
    ): UUID {
        val id = ids.next()
        tenantDsl
            .current()
            .insertInto(MEMO_COPY)
            .set(MEMO_COPY.ID, id)
            .set(MEMO_COPY.TENANT_ID, tenantDsl.tenant().value)
            .set(MEMO_COPY.MEMO_ID, memoId)
            .set(MEMO_COPY.RECIPIENT_KIND, recipient.kind.name)
            .set(MEMO_COPY.RECIPIENT_ID, recipient.id)
            .set(MEMO_COPY.SEND_WHEN, recipient.sendWhen.name)
            .set(MEMO_COPY.ADDED_BY, by)
            .set(MEMO_COPY.ADDED_AT, at)
            .execute()
        return id
    }

    /** Marks the memo changed (a new version for `If-Match`): its copy list is part of it. */
    fun touchMemo(
        memoId: UUID,
        now: OffsetDateTime,
    ) {
        tenantDsl
            .current()
            .update(MEMO)
            .set(MEMO.VERSION, MEMO.VERSION.plus(1))
            .set(MEMO.UPDATED_AT, now)
            .where(MEMO.ID.eq(memoId))
            .execute()
    }

    /** Removes an unsent copy. */
    fun remove(
        id: UUID,
        by: UUID,
        at: OffsetDateTime,
    ): Boolean =
        tenantDsl
            .current()
            .update(MEMO_COPY)
            .set(MEMO_COPY.REMOVED_AT, at)
            .set(MEMO_COPY.REMOVED_BY, by)
            .where(MEMO_COPY.ID.eq(id), MEMO_COPY.SENT_AT.isNull, MEMO_COPY.REMOVED_AT.isNull)
            .execute() == 1

    /** Marks a copy sent; false when it already was (a re-run sends nothing twice). */
    fun markSent(
        id: UUID,
        at: OffsetDateTime,
    ): Boolean =
        tenantDsl
            .current()
            .update(MEMO_COPY)
            .set(MEMO_COPY.SENT_AT, at)
            .where(MEMO_COPY.ID.eq(id), MEMO_COPY.SENT_AT.isNull)
            .execute() == 1

    fun deliver(
        copy: MemoCopy,
        userId: UUID,
        outcome: DeliveryOutcome,
        at: OffsetDateTime,
    ) {
        tenantDsl
            .current()
            .insertInto(MEMO_COPY_DELIVERY)
            .set(MEMO_COPY_DELIVERY.TENANT_ID, tenantDsl.tenant().value)
            .set(MEMO_COPY_DELIVERY.COPY_ID, copy.id)
            .set(MEMO_COPY_DELIVERY.MEMO_ID, copy.memoId)
            .set(MEMO_COPY_DELIVERY.USER_ID, userId)
            .set(MEMO_COPY_DELIVERY.OUTCOME, outcome.name)
            .set(MEMO_COPY_DELIVERY.AT, at)
            .onConflictDoNothing()
            .execute()
    }

    fun deliveries(memoId: UUID): List<CopyDelivery> =
        tenantDsl
            .current()
            .selectFrom(MEMO_COPY_DELIVERY)
            .where(MEMO_COPY_DELIVERY.MEMO_ID.eq(memoId))
            .orderBy(MEMO_COPY_DELIVERY.AT)
            .fetch { CopyDelivery(it.copyId, it.userId, DeliveryOutcome.valueOf(it.outcome), it.at.toInstant()) }

    /** The first open of [memoId] by [userId]; a later open changes nothing. */
    fun receipt(
        memoId: UUID,
        userId: UUID,
        documentVersion: Int?,
        at: OffsetDateTime,
    ) {
        tenantDsl
            .current()
            .insertInto(MEMO_RECEIPT)
            .set(MEMO_RECEIPT.TENANT_ID, tenantDsl.tenant().value)
            .set(MEMO_RECEIPT.MEMO_ID, memoId)
            .set(MEMO_RECEIPT.USER_ID, userId)
            .set(MEMO_RECEIPT.OPENED_AT, at)
            .set(MEMO_RECEIPT.DOCUMENT_VERSION, documentVersion)
            .onConflictDoNothing()
            .execute()
    }

    fun receipts(memoId: UUID): Map<UUID, MemoReceipt> =
        tenantDsl
            .current()
            .selectFrom(MEMO_RECEIPT)
            .where(MEMO_RECEIPT.MEMO_ID.eq(memoId))
            .fetch { MemoReceipt(it.userId, it.openedAt.toInstant(), it.documentVersion) }
            .associateBy { it.userId }

    private fun copy(r: MemoCopyRecord) =
        MemoCopy(
            r.id,
            r.memoId,
            RecipientKind.valueOf(r.recipientKind),
            r.recipientId,
            SendWhen.valueOf(r.sendWhen),
            r.addedBy,
            r.addedAt.toInstant(),
            r.sentAt?.toInstant(),
        )
}
