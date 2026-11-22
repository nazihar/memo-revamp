package com.memo.memo

import com.memo.audit.AuditLog
import com.memo.common.authz.Policy
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantDsl
import com.memo.memo.internal.MemoForumReaderStore
import com.memo.memo.internal.MemoReadable
import com.memo.memo.internal.MemoStore
import com.memo.memo.internal.MemoWorkflowSubject
import com.memo.memo.internal.Memos
import com.memo.memo.internal.ParticipantKind
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import com.memo.jooq.tables.references.MEMO_ as MEMO

/**
 * What another module may show of a memo its actor can read: its number (null until numbered), subject and status (the
 * memo's own: DRAFT, IN_REVIEW, RETURNED, APPROVED, REJECTED, WITHDRAWN, DISCARDED, CANCELLED or CLOSED).
 */
data class MemoBrief(
    val id: UUID,
    val reference: String?,
    val subject: String,
    val status: String,
)

/**
 * Memo readers for other modules (ADR-0059): whether the actor reads a memo, the memos of a list they read, and the
 * committee readers (`FORUM` participant rows) the forum module keeps in step with a memo's open submissions. The read
 * rule (ADR-0050) still applies to them: clearance is checked on every read. Inside a tenant transaction.
 */
@Service
class MemoReaders(
    private val store: MemoStore,
    private val forumRows: MemoForumReaderStore,
    private val readable: MemoReadable,
    private val policy: Policy,
    private val tenantDsl: TenantDsl,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    /** Whether the signed-in actor may read the memo [memoId] now. */
    fun canRead(memoId: UUID): Boolean = actor()?.let { store.visible(memoId, it, policy.clearanceRank()) } != null

    /** Of [memoIds], the memos the signed-in actor may read now, by id. */
    fun readable(memoIds: Collection<UUID>): Map<UUID, MemoBrief> {
        val user = actor()
        if (user == null || memoIds.isEmpty()) return emptyMap()
        return tenantDsl
            .current()
            .select(MEMO.ID, MEMO.REFERENCE, MEMO.SUBJECT, MEMO.STATUS)
            .from(MEMO)
            .where(MEMO.ID.`in`(memoIds), readable.condition(user))
            .fetch()
            .associate { it.value1()!! to MemoBrief(it.value1()!!, it.value2(), it.value3().orEmpty(), it.value4().orEmpty()) }
    }

    /**
     * The status of each memo of [memoIds] that exists, whoever reads it (design E07 §1.11): for a module that shows the
     * outcome of a memo to someone who needs it and may not read the memo (an action's verifier). The status says nothing
     * of the memo's content; the caller decides who sees it.
     */
    fun statuses(memoIds: Collection<UUID>): Map<UUID, String> {
        if (memoIds.isEmpty()) return emptyMap()
        return tenantDsl
            .current()
            .select(MEMO.ID, MEMO.STATUS)
            .from(MEMO)
            .where(MEMO.ID.`in`(memoIds))
            .fetch()
            .associate { it.value1()!! to it.value2().orEmpty() }
    }

    /**
     * Makes [users] the memo's committee readers: adds a `FORUM` row for each (a stronger row is left as it is) and
     * retires the `FORUM` rows of everyone else. Nothing happens for a subject that is not a memo. Each change is audited.
     */
    fun syncForum(
        memoId: UUID,
        users: Set<UUID>,
    ) {
        store.find(memoId) ?: return
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        val current = forumReaders(memoId)
        val added = users - current
        val retired = current - users
        added.sorted().forEach { store.addParticipant(memoId, it, ParticipantKind.FORUM, now, grantedBy = actor()) }
        forumRows.retire(memoId, retired, now)
        // Added people who already read it another way keep that row; only the rows written as FORUM are audited.
        val granted = forumReaders(memoId).intersect(added)
        if (granted.isNotEmpty()) audited("memo.reader.forum_added", memoId, granted)
        if (retired.isNotEmpty()) audited("memo.reader.forum_retired", memoId, retired)
    }

    private fun forumReaders(memoId: UUID): Set<UUID> = forumRows.active(memoId)

    private fun audited(
        action: String,
        memoId: UUID,
        users: Set<UUID>,
    ) = audit.record(action, Memos.OBJECT_TYPE, memoId.toString(), after = mapOf("users" to users.map(UUID::toString).sorted()))

    private fun actor(): UUID? = (ActorContext.currentOrNull()?.actor as? Actor.User)?.userId

    companion object {
        /** The workflow subject type of memos: submissions of other subjects have no memo readers. */
        const val SUBJECT_TYPE = MemoWorkflowSubject.TYPE
    }
}
