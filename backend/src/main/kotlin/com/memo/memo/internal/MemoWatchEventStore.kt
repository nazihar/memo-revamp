package com.memo.memo.internal

import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.MEMO_WATCH
import com.memo.jooq.tables.references.MEMO_WATCH_EVENT
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** `memo_watch_event`: news on followed memos until told in the app and by email. Inside a tenant transaction. */
@Repository
class MemoWatchEventStore(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    /** Records that [kind] happened to [memo], for each of [users]. */
    fun events(
        memo: UUID,
        users: Collection<UUID>,
        kind: WatchEventKind,
    ) {
        if (users.isEmpty()) return
        val dsl = tenantDsl.current()
        val tenant = tenantDsl.tenant().value
        val at = now()
        val insert =
            dsl.insertInto(
                MEMO_WATCH_EVENT,
                MEMO_WATCH_EVENT.ID,
                MEMO_WATCH_EVENT.TENANT_ID,
                MEMO_WATCH_EVENT.MEMO_ID,
                MEMO_WATCH_EVENT.USER_ID,
                MEMO_WATCH_EVENT.KIND,
                MEMO_WATCH_EVENT.CREATED_AT,
            )
        users.fold(insert) { q, user -> q.values(ids.next(), tenant, memo, user, kind.name, at) }.execute()
    }

    /**
     * News not told in the app yet, per person and memo, whose latest event is older than [quietSince] (batched per memo
     * with an idle window), for memos still followed.
     */
    fun pending(quietSince: Instant): List<PendingWatch> {
        val e = MEMO_WATCH_EVENT
        val latest = DSL.max(e.CREATED_AT)
        return tenantDsl
            .current()
            .select(e.USER_ID, e.MEMO_ID, DSL.count(), MEMO_WATCH.REASON)
            .from(e)
            .join(MEMO_WATCH)
            .on(MEMO_WATCH.MEMO_ID.eq(e.MEMO_ID), MEMO_WATCH.USER_ID.eq(e.USER_ID))
            .where(e.NOTIFIED_AT.isNull)
            .groupBy(e.USER_ID, e.MEMO_ID, MEMO_WATCH.REASON)
            .having(latest.lt(quietSince.atOffset(ZoneOffset.UTC)))
            .fetch {
                PendingWatch(
                    requireNotNull(it.value1()),
                    requireNotNull(it.value2()),
                    it.value3(),
                    WatchReason.valueOf(requireNotNull(it.value4())),
                )
            }
    }

    fun markNotified(
        user: UUID,
        memo: UUID,
    ) {
        tenantDsl
            .current()
            .update(MEMO_WATCH_EVENT)
            .set(MEMO_WATCH_EVENT.NOTIFIED_AT, now())
            .where(MEMO_WATCH_EVENT.USER_ID.eq(user), MEMO_WATCH_EVENT.MEMO_ID.eq(memo), MEMO_WATCH_EVENT.NOTIFIED_AT.isNull)
            .execute()
    }

    /** Per person, how many followed memos have news not emailed yet. */
    fun unemailed(): Map<UUID, Int> =
        tenantDsl
            .current()
            .select(MEMO_WATCH_EVENT.USER_ID, DSL.countDistinct(MEMO_WATCH_EVENT.MEMO_ID))
            .from(MEMO_WATCH_EVENT)
            .where(MEMO_WATCH_EVENT.EMAILED_AT.isNull)
            .groupBy(MEMO_WATCH_EVENT.USER_ID)
            .fetch()
            .associate { requireNotNull(it.value1()) to it.value2() }

    /** Marks [user]'s news emailed: [memo] only, or all of it. */
    fun markEmailed(
        user: UUID,
        memo: UUID? = null,
    ) {
        tenantDsl
            .current()
            .update(MEMO_WATCH_EVENT)
            .set(MEMO_WATCH_EVENT.EMAILED_AT, now())
            .where(MEMO_WATCH_EVENT.USER_ID.eq(user), MEMO_WATCH_EVENT.EMAILED_AT.isNull)
            .and(memo?.let { MEMO_WATCH_EVENT.MEMO_ID.eq(it) } ?: DSL.noCondition())
            .execute()
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
}
