package com.memo.memo.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.MEMO_PARTICIPANT
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The `FORUM` participant rows of memos (ADR-0059): who reads a memo as a committee officer now, and retiring those
 * rows. Adding one goes through [MemoStore.addParticipant], so it never replaces a stronger row. Inside a tenant
 * transaction.
 */
@Repository
class MemoForumReaderStore(
    private val tenantDsl: TenantDsl,
) {
    /** The people with an active `FORUM` row on [memo]. */
    fun active(memo: UUID): Set<UUID> =
        tenantDsl
            .current()
            .select(MEMO_PARTICIPANT.USER_ID)
            .from(MEMO_PARTICIPANT)
            .where(
                MEMO_PARTICIPANT.MEMO_ID.eq(memo),
                MEMO_PARTICIPANT.KIND.eq(ParticipantKind.FORUM.name),
                MEMO_PARTICIPANT.REVOKED_AT.isNull,
            ).fetch(MEMO_PARTICIPANT.USER_ID)
            .filterNotNull()
            .toSet()

    /** Retires the active `FORUM` rows of [users] on [memo]; rows of other kinds are left as they are. */
    fun retire(
        memo: UUID,
        users: Collection<UUID>,
        now: OffsetDateTime,
    ) {
        if (users.isEmpty()) return
        tenantDsl
            .current()
            .update(MEMO_PARTICIPANT)
            .set(MEMO_PARTICIPANT.REVOKED_AT, now)
            .where(
                MEMO_PARTICIPANT.MEMO_ID.eq(memo),
                MEMO_PARTICIPANT.USER_ID.`in`(users),
                MEMO_PARTICIPANT.KIND.eq(ParticipantKind.FORUM.name),
                MEMO_PARTICIPANT.REVOKED_AT.isNull,
            ).execute()
    }
}
