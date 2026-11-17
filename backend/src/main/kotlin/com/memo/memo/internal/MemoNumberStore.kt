package com.memo.memo.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.MEMO_NUMBER_COUNTER
import com.memo.jooq.tables.references.MEMO_PARTICIPANT
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID
import com.memo.jooq.tables.references.MEMO_ as MEMO

/** Which counter a number comes from (ADR-0044): a series, a period (`ALL`, `2026`, `FY2026-27`) and a scope (a unit, or empty). */
data class NumberKey(
    val series: String,
    val period: String,
    val scope: String,
)

/** A counter: the last number taken under a [key]. */
data class NumberCounter(
    val key: NumberKey,
    val lastNumber: Long,
    val startedByAdmin: Boolean,
)

/** A number in the register, with its memo; [subject] only when the reader may see the memo. */
data class IssuedNumber(
    val memoId: UUID,
    val reference: String,
    val sequence: Long,
    val status: MemoStatus,
    val subject: String?,
)

/** Who reads the register: the subject is shown only for memos they take part in and are cleared for. */
data class RegisterReader(
    val user: UUID,
    val clearanceRank: Int,
)

/**
 * Memo numbers (ADR-0044): the counters, numbers written once onto memos, and the register read back by counter. Every
 * method runs inside a tenant transaction.
 */
@Repository
class MemoNumberStore(
    private val tenantDsl: TenantDsl,
) {
    /**
     * The next number of [key]: the counter row is created or incremented and stays locked until commit, so numbers of one
     * counter are taken one transaction at a time, and a rollback gives the number back (gapless).
     */
    fun next(key: NumberKey): Long =
        tenantDsl
            .current()
            .insertInto(MEMO_NUMBER_COUNTER)
            .set(MEMO_NUMBER_COUNTER.TENANT_ID, tenantDsl.tenant().value)
            .set(MEMO_NUMBER_COUNTER.SERIES, key.series)
            .set(MEMO_NUMBER_COUNTER.PERIOD, key.period)
            .set(MEMO_NUMBER_COUNTER.SCOPE, key.scope)
            .set(MEMO_NUMBER_COUNTER.LAST_NUMBER, 1L)
            .onConflict(MEMO_NUMBER_COUNTER.TENANT_ID, MEMO_NUMBER_COUNTER.SERIES, MEMO_NUMBER_COUNTER.PERIOD, MEMO_NUMBER_COUNTER.SCOPE)
            .doUpdate()
            .set(MEMO_NUMBER_COUNTER.LAST_NUMBER, MEMO_NUMBER_COUNTER.LAST_NUMBER.plus(1))
            .returning(MEMO_NUMBER_COUNTER.LAST_NUMBER)
            .fetchSingle()
            .lastNumber

    /** Creates the counter of [key] so that it hands out [next] first; false when it exists (started or used). */
    fun start(
        key: NumberKey,
        next: Long,
    ): Boolean =
        tenantDsl
            .current()
            .insertInto(MEMO_NUMBER_COUNTER)
            .set(MEMO_NUMBER_COUNTER.TENANT_ID, tenantDsl.tenant().value)
            .set(MEMO_NUMBER_COUNTER.SERIES, key.series)
            .set(MEMO_NUMBER_COUNTER.PERIOD, key.period)
            .set(MEMO_NUMBER_COUNTER.SCOPE, key.scope)
            .set(MEMO_NUMBER_COUNTER.LAST_NUMBER, next - 1)
            .set(MEMO_NUMBER_COUNTER.STARTED_BY_ADMIN, true)
            .onConflictDoNothing()
            .execute() == 1

    /** Every counter, by series, period and scope. */
    fun counters(): List<NumberCounter> =
        tenantDsl
            .current()
            .selectFrom(MEMO_NUMBER_COUNTER)
            .orderBy(MEMO_NUMBER_COUNTER.SERIES, MEMO_NUMBER_COUNTER.PERIOD, MEMO_NUMBER_COUNTER.SCOPE)
            .fetch { NumberCounter(NumberKey(it.series, it.period, it.scope), it.lastNumber, it.startedByAdmin == true) }

    /** Writes [reference], number [sequence] of [key], onto a memo without one; false when it has one (it never changes). */
    fun setNumber(
        memoId: UUID,
        reference: String,
        key: NumberKey,
        sequence: Long,
        now: OffsetDateTime,
    ): Boolean =
        tenantDsl
            .current()
            .update(MEMO)
            .set(MEMO.REFERENCE, reference)
            .set(MEMO.NUMBER_SERIES, key.series)
            .set(MEMO.NUMBER_PERIOD, key.period)
            .set(MEMO.NUMBER_SCOPE, key.scope)
            .set(MEMO.NUMBER_SEQUENCE, sequence)
            .set(MEMO.NUMBERED_AT, now)
            .where(MEMO.ID.eq(memoId), MEMO.REFERENCE.isNull)
            .execute() == 1

    /** The numbers of [key] in order, after [after], at most [limit] (keyset paging). */
    fun issued(
        key: NumberKey,
        after: Long,
        limit: Int,
        reader: RegisterReader,
    ): List<IssuedNumber> {
        val reads =
            DSL.exists(
                DSL
                    .selectOne()
                    .from(MEMO_PARTICIPANT)
                    .where(MEMO_PARTICIPANT.MEMO_ID.eq(MEMO.ID), MEMO_PARTICIPANT.USER_ID.eq(reader.user)),
            )
        return tenantDsl
            .current()
            .select(MEMO.ID, MEMO.REFERENCE, MEMO.NUMBER_SEQUENCE, MEMO.STATUS, MEMO.SUBJECT, MEMO.CLASSIFICATION_RANK, DSL.field(reads))
            .from(MEMO)
            .where(of(key), MEMO.NUMBER_SEQUENCE.gt(after))
            .orderBy(MEMO.NUMBER_SEQUENCE)
            .limit(limit)
            .fetch { r ->
                val visible = r.value7() == true && r.value6()!! <= reader.clearanceRank
                IssuedNumber(r.value1()!!, r.value2()!!, r.value3()!!, MemoStatus.valueOf(r.value4()!!), r.value5().takeIf { visible })
            }
    }

    /** The lowest number any memo has under [key]. */
    fun first(key: NumberKey): Long? =
        tenantDsl
            .current()
            .select(DSL.min(MEMO.NUMBER_SEQUENCE))
            .from(MEMO)
            .where(of(key))
            .fetchOne(0, Long::class.javaObjectType)

    /** The numbers in [range] no memo has under [key] (at most [limit]), and how many: a complete register has none. */
    fun missing(
        key: NumberKey,
        range: LongRange,
        limit: Int,
    ): Pair<List<Long>, Long> {
        if (range.isEmpty()) return emptyList<Long>() to 0
        val gaps =
            """
            select s from generate_series(?::bigint, ?::bigint) s
            where not exists (select 1 from memo m where m.number_series = ? and m.number_period = ? and m.number_scope = ?
                                                     and m.number_sequence = s)
            """.trimIndent()
        val binds = arrayOf<Any>(range.first, range.last, key.series, key.period, key.scope)
        val dsl = tenantDsl.current()
        val first = dsl.fetch("$gaps order by s limit ?", *binds, limit).map { it.get(0, Long::class.java) }
        val count = dsl.fetchOne("select count(*) from ($gaps) gaps", *binds)?.get(0, Long::class.java) ?: 0
        return first to count
    }

    private fun of(key: NumberKey) =
        MEMO.NUMBER_SERIES
            .eq(key.series)
            .and(MEMO.NUMBER_PERIOD.eq(key.period))
            .and(MEMO.NUMBER_SCOPE.eq(key.scope))
}
