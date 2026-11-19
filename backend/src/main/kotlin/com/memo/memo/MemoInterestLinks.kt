package com.memo.memo

import com.memo.audit.AuditLog
import com.memo.common.authz.Clearances
import com.memo.common.tenant.TenantDsl
import com.memo.memo.internal.MemoInterestStore
import com.memo.memo.internal.MemoReadable
import com.memo.memo.internal.MemoStore
import com.memo.memo.internal.Memos
import org.springframework.stereotype.Component
import java.util.UUID
import com.memo.jooq.tables.references.MEMO_ as MEMO

/** The people with a declared interest in a memo, with the memo's number (null until it is numbered). */
data class MemoInterestSet(
    val memoId: UUID,
    val reference: String?,
    val users: Set<UUID>,
)

/**
 * Memo interests for the meeting module (SOD-4, ADR-0062, design E04 Phase 4 §1.4, §2): who has an interest in a memo,
 * so a committee item made from it carries their declarations; recording the interest of someone who declared one on the
 * item at a meeting, so the workflow never asks them to decide on the memo later; and who among some people reads the
 * memo by its own rule, for the meeting register's "also reads the memo". The reason given stays in the memo module and
 * the audit carries ids only. No access check: the meeting module decides who acts. Inside a tenant transaction.
 */
@Component
class MemoInterestLinks(
    private val interests: MemoInterestStore,
    private val memos: MemoStore,
    private val readable: MemoReadable,
    private val clearances: Clearances,
    private val tenantDsl: TenantDsl,
    private val audit: AuditLog,
) {
    /** The people with a declared interest in [memoId]. */
    fun of(memoId: UUID): Set<UUID> = interests.of(memoId)

    /** The interests of each memo of [memoIds] that has at least one, with its number. */
    fun ofMemos(memoIds: Collection<UUID>): Map<UUID, MemoInterestSet> {
        val users = interests.ofMemos(memoIds)
        if (users.isEmpty()) return emptyMap()
        val references =
            tenantDsl
                .current()
                .select(MEMO.ID, MEMO.REFERENCE)
                .from(MEMO)
                .where(MEMO.ID.`in`(users.keys))
                .fetch()
                .associate { it.value1()!! to it.value2() }
        return users.mapValues { (memo, people) -> MemoInterestSet(memo, references[memo], people) }
    }

    /**
     * Records that [userId] has an interest in [memoId] because they declared one at a meeting ([declarationId]), entered
     * by [recordedBy], with [reason] (a fixed text the meeting module writes, never the declaration's words). Idempotent:
     * false, and nothing audited, when they had one already or there is no such memo. Audited `memo.interest.declared`
     * with `via: meeting`.
     */
    fun recordFromMeeting(
        memoId: UUID,
        userId: UUID,
        recordedBy: UUID,
        reason: String,
        declarationId: UUID,
    ): Boolean {
        val memo = memos.find(memoId) ?: return false
        val added = interests.add(memo.id, userId, recordedBy, reason.take(MAX_REASON))
        if (added) {
            audit.record(
                "memo.interest.declared",
                Memos.OBJECT_TYPE,
                memo.id.toString(),
                after = mapOf("userId" to userId.toString(), "via" to "meeting", "declarationId" to declarationId.toString()),
            )
        }
        return added
    }

    /**
     * The people among [userIds] who read [memoId] now by the memo's own rule (ADR-0050): an active participant row of any
     * kind and a clearance at least the memo's level. Unit grants (`memo.read.unit`, an oversight grant) are not counted.
     */
    fun readersAmong(
        memoId: UUID,
        userIds: Collection<UUID>,
    ): Set<UUID> {
        val memo = memos.find(memoId) ?: return emptySet()
        val participants = readable.participantsAmong(memo.id, userIds)
        val ranks = clearances.rankOf(participants)
        return participants.filter { (ranks[it] ?: 0) >= memo.classificationRank }.toSet()
    }

    private companion object {
        /** The column's limit (memo/V4). */
        const val MAX_REASON = 4000
    }
}
