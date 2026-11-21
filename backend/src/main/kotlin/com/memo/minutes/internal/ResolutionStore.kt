package com.memo.minutes.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.MinutesResolutionRecord
import com.memo.jooq.tables.references.MINUTES_DECISION
import com.memo.jooq.tables.references.MINUTES_RESOLUTION
import com.memo.jooq.tables.references.MINUTES_RESOLUTION_COUNTER
import com.memo.minutes.ResolutionState
import org.jooq.Condition
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** A resolution number given (design E06 §1.3): in force, or void with the correction's reason. */
data class ResolutionRow(
    val id: UUID,
    val decisionId: UUID,
    val forumId: UUID,
    val meetingId: UUID,
    val period: String,
    val sequence: Long,
    val number: String,
    val numberedAt: Instant,
    val state: ResolutionState,
    val voidReason: String?,
    val voidedAt: Instant?,
    val voidedByEntrySeq: Int?,
)

/** A committee's counter for a period: the last number given, and where it started (1, or an administrator's start). */
data class CounterRow(
    val forumId: UUID,
    val period: String,
    val lastNumber: Long,
    val firstNumber: Long,
    val startedByAdmin: Boolean,
)

/** A number given in a period, as the register check reads it. */
data class GivenNumber(
    val sequence: Long,
    val state: ResolutionState,
)

/**
 * Resolution numbers given (ADR-0065), inside a tenant transaction: the numbers, their voids, and the register read back by
 * committee and period in number order. The app role may change a number's void only; a trigger repeats the rules. Readers
 * reach them through [ResolutionAccess]; the counters are [ResolutionCounters].
 */
@Repository
class ResolutionStore(
    private val tenantDsl: TenantDsl,
) {
    /** Whether [number] is written on a resolution of the tenant already (two formats may render the same text). */
    fun taken(number: String): Boolean =
        tenantDsl.current().fetchExists(DSL.selectOne().from(MINUTES_RESOLUTION).where(MINUTES_RESOLUTION.NUMBER.eq(number)))

    fun insert(row: ResolutionRow) {
        val r = MINUTES_RESOLUTION
        tenantDsl
            .current()
            .insertInto(r)
            .set(r.ID, row.id)
            .set(r.TENANT_ID, tenantDsl.tenant().value)
            .set(r.DECISION_ID, row.decisionId)
            .set(r.FORUM_ID, row.forumId)
            .set(r.MEETING_ID, row.meetingId)
            .set(r.PERIOD, row.period)
            .set(r.SEQUENCE, row.sequence)
            .set(r.NUMBER, row.number)
            .set(r.NUMBERED_AT, row.numberedAt.atOffset(ZoneOffset.UTC))
            .set(r.STATE, row.state.name)
            .execute()
    }

    /** Voids [id] with [reason], by entry [seq] of its decision; false when it was void already. */
    fun void(
        id: UUID,
        reason: String,
        at: Instant,
        seq: Int,
    ): Boolean {
        val r = MINUTES_RESOLUTION
        return tenantDsl
            .current()
            .update(r)
            .set(r.STATE, ResolutionState.VOID.name)
            .set(r.VOID_REASON, reason)
            .set(r.VOIDED_AT, at.atOffset(ZoneOffset.UTC))
            .set(r.VOIDED_BY_ENTRY_SEQ, seq)
            .where(r.ID.eq(id), r.STATE.eq(ResolutionState.IN_FORCE.name))
            .execute() == 1
    }

    fun find(id: UUID): ResolutionRow? = where(MINUTES_RESOLUTION.ID.eq(id)).firstOrNull()

    /** [decisionId]'s number in force; null when it has none. */
    fun inForce(decisionId: UUID): ResolutionRow? =
        where(MINUTES_RESOLUTION.DECISION_ID.eq(decisionId), MINUTES_RESOLUTION.STATE.eq(ResolutionState.IN_FORCE.name)).firstOrNull()

    /** The numbers in force of [decisionIds], by decision. */
    fun inForce(decisionIds: Collection<UUID>): Map<UUID, ResolutionRow> =
        if (decisionIds.isEmpty()) {
            emptyMap()
        } else {
            where(MINUTES_RESOLUTION.DECISION_ID.`in`(decisionIds), MINUTES_RESOLUTION.STATE.eq(ResolutionState.IN_FORCE.name))
                .associateBy { it.decisionId }
        }

    /** Every number [decisionId] was given, the first first. */
    fun ofDecision(decisionId: UUID): List<ResolutionRow> = where(MINUTES_RESOLUTION.DECISION_ID.eq(decisionId))

    /** The numbers of the decisions on [memoId]'s submissions, the first first. */
    fun ofMemo(memoId: UUID): List<ResolutionRow> {
        val decisions = DSL.select(MINUTES_DECISION.ID).from(MINUTES_DECISION).where(MINUTES_DECISION.MEMO_ID.eq(memoId))
        return where(MINUTES_RESOLUTION.DECISION_ID.`in`(decisions))
    }

    /**
     * A page of [forumId]'s register for [period] in number order, the latest first (keyset on the sequence: those below
     * [before]), at most [limit]; [state] filters in force or void.
     */
    fun page(
        forumId: UUID,
        period: String,
        state: ResolutionState?,
        before: Long?,
        limit: Int,
    ): List<ResolutionRow> {
        val r = MINUTES_RESOLUTION
        return tenantDsl
            .current()
            .selectFrom(r)
            .where(
                r.FORUM_ID.eq(forumId),
                r.PERIOD.eq(period),
                state?.let { r.STATE.eq(it.name) } ?: DSL.noCondition(),
                before?.let { r.SEQUENCE.lt(it) } ?: DSL.noCondition(),
            ).orderBy(r.SEQUENCE.desc())
            .limit(limit)
            .fetch { it.row() }
    }

    private fun where(vararg conditions: Condition): List<ResolutionRow> =
        tenantDsl
            .current()
            .selectFrom(MINUTES_RESOLUTION)
            .where(*conditions)
            .orderBy(MINUTES_RESOLUTION.NUMBERED_AT, MINUTES_RESOLUTION.SEQUENCE)
            .fetch { it.row() }
}

/**
 * The committees' counters (ADR-0065, ADR-0044), inside a tenant transaction: the next number of a period, the starting
 * number, and what the register check reads. The app role may change a counter's last number only (it only grows).
 */
@Repository
class ResolutionCounters(
    private val tenantDsl: TenantDsl,
) {
    /**
     * The next number of [forumId]'s [period]: the counter row is created or incremented and stays locked until commit, so
     * a committee's numbers are taken one transaction at a time and a rollback gives the number back (gapless).
     */
    fun next(
        forumId: UUID,
        period: String,
    ): Long {
        val c = MINUTES_RESOLUTION_COUNTER
        return tenantDsl
            .current()
            .insertInto(c)
            .set(c.TENANT_ID, tenantDsl.tenant().value)
            .set(c.FORUM_ID, forumId)
            .set(c.PERIOD, period)
            .set(c.LAST_NUMBER, 1L)
            .onConflict(c.TENANT_ID, c.FORUM_ID, c.PERIOD)
            .doUpdate()
            .set(c.LAST_NUMBER, c.LAST_NUMBER.plus(1))
            .returning(c.LAST_NUMBER)
            .fetchSingle()
            .lastNumber
    }

    /** Creates [forumId]'s counter for [period] so that it gives [next] first; false when it exists (started or used). */
    fun start(
        forumId: UUID,
        period: String,
        next: Long,
    ): Boolean {
        val c = MINUTES_RESOLUTION_COUNTER
        return tenantDsl
            .current()
            .insertInto(c)
            .set(c.TENANT_ID, tenantDsl.tenant().value)
            .set(c.FORUM_ID, forumId)
            .set(c.PERIOD, period)
            .set(c.LAST_NUMBER, next - 1)
            .set(c.FIRST_NUMBER, next)
            .set(c.STARTED_BY_ADMIN, true)
            .onConflictDoNothing()
            .execute() == 1
    }

    /** [forumId]'s counter for [period]; null when it has none. */
    fun counter(
        forumId: UUID,
        period: String,
    ): CounterRow? = counters(MINUTES_RESOLUTION_COUNTER.FORUM_ID.eq(forumId), MINUTES_RESOLUTION_COUNTER.PERIOD.eq(period)).firstOrNull()

    /** [forumId]'s counters, by period. */
    fun counters(forumId: UUID): List<CounterRow> = counters(MINUTES_RESOLUTION_COUNTER.FORUM_ID.eq(forumId))

    private fun counters(vararg conditions: Condition): List<CounterRow> =
        tenantDsl
            .current()
            .selectFrom(MINUTES_RESOLUTION_COUNTER)
            .where(*conditions)
            .orderBy(MINUTES_RESOLUTION_COUNTER.PERIOD)
            .fetch { CounterRow(it.forumId, it.period, it.lastNumber, it.firstNumber ?: 1, it.startedByAdmin == true) }

    /** Every number given in [forumId]'s [period], in order, for the register check (one aggregate-sized read). */
    fun sequences(
        forumId: UUID,
        period: String,
    ): List<GivenNumber> {
        val r = MINUTES_RESOLUTION
        return tenantDsl
            .current()
            .select(r.SEQUENCE, r.STATE)
            .from(r)
            .where(r.FORUM_ID.eq(forumId), r.PERIOD.eq(period))
            .orderBy(r.SEQUENCE)
            .fetch { GivenNumber(checkNotNull(it.value1()), ResolutionState.valueOf(checkNotNull(it.value2()))) }
    }
}

private fun MinutesResolutionRecord.row() =
    ResolutionRow(
        id = id,
        decisionId = decisionId,
        forumId = forumId,
        meetingId = meetingId,
        period = period,
        sequence = sequence,
        number = number,
        numberedAt = numberedAt.toInstant(),
        state = ResolutionState.valueOf(state ?: ResolutionState.IN_FORCE.name),
        voidReason = voidReason,
        voidedAt = voidedAt?.toInstant(),
        voidedByEntrySeq = voidedByEntrySeq,
    )
