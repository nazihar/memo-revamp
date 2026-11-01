package com.memo.org.internal

import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.OrgReportingLineRecord
import com.memo.jooq.tables.references.ORG_REPORTING_LINE
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class ReportingLine(
    val id: UUID,
    val positionId: UUID,
    val reportsToPositionId: UUID,
    val period: Period,
    val status: RecordStatus,
    val version: Long,
)

/** Reporting lines between positions (ADR-0026). Every method runs inside a tenant transaction. */
@Repository
class ReportingLineStore(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    /** Serialises reporting-line changes of the tenant until commit, so concurrent changes cannot build a cycle. */
    fun lockAll() {
        tenantDsl.current().fetch("select pg_advisory_xact_lock(hashtextextended(?, 0))", "org.lines:${tenantDsl.tenant()}")
    }

    fun find(id: UUID): ReportingLine? =
        tenantDsl
            .current()
            .selectFrom(ORG_REPORTING_LINE)
            .where(ORG_REPORTING_LINE.ID.eq(id))
            .fetchOne()
            ?.toLine()

    fun ofPosition(position: UUID): List<ReportingLine> =
        tenantDsl
            .current()
            .selectFrom(ORG_REPORTING_LINE)
            .where(ORG_REPORTING_LINE.POSITION_ID.eq(position))
            .orderBy(ORG_REPORTING_LINE.VALID_FROM.desc(), ORG_REPORTING_LINE.ID)
            .fetch { it.toLine() }

    /** Every active line of the tenant (the cycle check walks them; a few thousand rows at most). */
    fun active(): List<ReportingLine> =
        tenantDsl
            .current()
            .selectFrom(ORG_REPORTING_LINE)
            .where(ORG_REPORTING_LINE.STATUS.eq(RecordStatus.ACTIVE.name))
            .fetch { it.toLine() }

    fun insert(
        position: UUID,
        reportsTo: UUID,
        period: Period,
    ): ReportingLine {
        val now = now()
        return tenantDsl
            .current()
            .insertInto(ORG_REPORTING_LINE)
            .set(ORG_REPORTING_LINE.ID, ids.next())
            .set(ORG_REPORTING_LINE.TENANT_ID, tenantDsl.tenant().value)
            .set(ORG_REPORTING_LINE.POSITION_ID, position)
            .set(ORG_REPORTING_LINE.REPORTS_TO_POSITION_ID, reportsTo)
            .set(ORG_REPORTING_LINE.VALID_FROM, period.from)
            .set(ORG_REPORTING_LINE.VALID_TO, period.to)
            .set(ORG_REPORTING_LINE.CREATED_AT, now)
            .set(ORG_REPORTING_LINE.UPDATED_AT, now)
            .returning()
            .fetchSingle()
            .toLine()
    }

    /** Guarded update of an ACTIVE line on [expectedVersion]. */
    fun update(
        id: UUID,
        expectedVersion: Long,
        period: Period,
        status: RecordStatus,
    ): Boolean =
        tenantDsl
            .current()
            .update(ORG_REPORTING_LINE)
            .set(ORG_REPORTING_LINE.VALID_FROM, period.from)
            .set(ORG_REPORTING_LINE.VALID_TO, period.to)
            .set(ORG_REPORTING_LINE.STATUS, status.name)
            .set(ORG_REPORTING_LINE.VERSION, ORG_REPORTING_LINE.VERSION.plus(1))
            .set(ORG_REPORTING_LINE.UPDATED_AT, now())
            .where(ORG_REPORTING_LINE.ID.eq(id), ORG_REPORTING_LINE.VERSION.eq(expectedVersion))
            .and(ORG_REPORTING_LINE.STATUS.eq(RecordStatus.ACTIVE.name))
            .execute() == 1

    /** Active lines from or to [position] still running on or after [date]. */
    fun activeAfter(
        position: UUID,
        date: LocalDate,
    ): Int =
        tenantDsl.current().fetchCount(
            ORG_REPORTING_LINE,
            ORG_REPORTING_LINE.POSITION_ID.eq(position).or(ORG_REPORTING_LINE.REPORTS_TO_POSITION_ID.eq(position)),
            ORG_REPORTING_LINE.STATUS.eq(RecordStatus.ACTIVE.name),
            ORG_REPORTING_LINE.VALID_TO.isNull.or(ORG_REPORTING_LINE.VALID_TO.gt(date)),
        )

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private fun OrgReportingLineRecord.toLine() =
        ReportingLine(id, positionId, reportsToPositionId, Period(validFrom, validTo), RecordStatus.valueOf(status!!), version!!)
}
