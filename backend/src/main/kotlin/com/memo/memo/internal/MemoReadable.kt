package com.memo.memo.internal

import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.jooq.tables.references.MEMO_PARTICIPANT
import org.jooq.Condition
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import com.memo.jooq.tables.references.MEMO_ as MEMO

/**
 * Who may read a memo (ADR-0050, design E03 §5), as one SQL condition over `memo` for every get, list, inbox, link and
 * register query: the memo's classification at most the reader's clearance, and either an active participant row
 * (not revoked, a share not past its expiry) or a `memo.read.unit` grant whose scope covers the unit the memo comes
 * from. A way in never lifts classification. Unit scopes come from the actor's grants, so [user] is the actor.
 */
@Component
class MemoReadable(
    private val policy: Policy,
    private val clock: Clock,
) {
    /** Rows of `memo` that [user], the actor, may read at [clearanceRank]. */
    fun condition(
        user: UUID,
        clearanceRank: Int = policy.clearanceRank(),
    ): Condition = MEMO.CLASSIFICATION_RANK.le(clearanceRank).and(DSL.exists(participant(MEMO.ID, user)).or(units()))

    /** Whether [user] has an active participant row for the memo in [memoId]: the part of the rule that is not units. */
    fun participant(
        memoId: Field<UUID?>,
        user: UUID,
    ) = DSL
        .selectOne()
        .from(MEMO_PARTICIPANT)
        .where(MEMO_PARTICIPANT.MEMO_ID.eq(memoId), MEMO_PARTICIPANT.USER_ID.eq(user), active())

    /** An active participant row: not revoked, and not a lapsed share. */
    fun active(): Condition =
        MEMO_PARTICIPANT.REVOKED_AT.isNull.and(MEMO_PARTICIPANT.EXPIRES_AT.isNull.or(MEMO_PARTICIPANT.EXPIRES_AT.gt(now())))

    /** Memos whose origin unit is in one of the actor's `memo.read.unit` scopes (none for most people). */
    private fun units(): Condition =
        if (policy.holdsAnywhere(Permission.MEMO_READ_UNIT)) {
            policy.condition(Permission.MEMO_READ_UNIT, unit = MEMO.ORIGIN_UNIT_ID)
        } else {
            DSL.falseCondition()
        }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
}
