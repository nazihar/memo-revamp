package com.memo.memo.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.MEMO_PARTICIPANT
import com.memo.workflow.SubjectSummary
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import com.memo.jooq.tables.references.MEMO_ as MEMO

/** A row of a box (drafts, sent, closed). */
data class MemoListItem(
    val id: UUID,
    val reference: String,
    val subject: String,
    val status: MemoStatus,
    val round: Int,
    val updatedAt: Instant,
)

/** List reads of memos (design E01 §10): one query per page, keyset on `(updated_at desc, id)`. Inside a tenant transaction. */
@Repository
class MemoListStore(
    private val tenantDsl: TenantDsl,
) {
    fun summaries(ids: Collection<UUID>): Map<UUID, SubjectSummary> {
        if (ids.isEmpty()) return emptyMap()
        return tenantDsl
            .current()
            .select(MEMO.ID, MEMO.REFERENCE, MEMO.SUBJECT, MEMO.CLASSIFICATION_RANK)
            .from(MEMO)
            .where(MEMO.ID.`in`(ids))
            .fetch()
            .associate { it.value1()!! to SubjectSummary(it.value2()!!, it.value3()!!, it.value4()!!) }
    }

    /** Which of [ids] [user] takes part in and is cleared for ([clearanceRank]). */
    fun visibleAmong(
        ids: Collection<UUID>,
        user: UUID,
        clearanceRank: Int,
    ): Set<UUID> {
        if (ids.isEmpty()) return emptySet()
        return tenantDsl
            .current()
            .select(MEMO.ID)
            .from(MEMO)
            .join(MEMO_PARTICIPANT)
            .on(MEMO_PARTICIPANT.MEMO_ID.eq(MEMO.ID), MEMO_PARTICIPANT.USER_ID.eq(user))
            .where(MEMO.ID.`in`(ids), MEMO.CLASSIFICATION_RANK.le(clearanceRank))
            .fetchSet(MEMO.ID)
            .filterNotNull()
            .toSet()
    }

    fun anyOf(initiator: UUID): Boolean = tenantDsl.current().fetchExists(MEMO, MEMO.INITIATOR_ID.eq(initiator))

    /** [initiator]'s memos in [statuses], most recently changed first, after the keyset position [after]. */
    fun box(
        initiator: UUID,
        statuses: Set<MemoStatus>,
        after: Pair<Instant, UUID>?,
        limit: Int,
    ): List<MemoListItem> =
        tenantDsl
            .current()
            .select(MEMO.ID, MEMO.REFERENCE, MEMO.SUBJECT, MEMO.STATUS, MEMO.CURRENT_ROUND, MEMO.UPDATED_AT)
            .from(MEMO)
            .where(
                listOfNotNull(
                    MEMO.INITIATOR_ID.eq(initiator),
                    MEMO.STATUS.`in`(statuses.map { it.name }),
                    after?.let { (at, id) -> DSL.row(MEMO.UPDATED_AT, MEMO.ID).lt(at.atOffset(ZoneOffset.UTC), id) },
                ),
            ).orderBy(MEMO.UPDATED_AT.desc(), MEMO.ID.desc())
            .limit(limit)
            .fetch {
                MemoListItem(
                    it.value1()!!,
                    it.value2()!!,
                    it.value3()!!,
                    MemoStatus.valueOf(it.value4()!!),
                    it.value5()!!,
                    it.value6()!!.toInstant(),
                )
            }
}
