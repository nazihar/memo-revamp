package com.memo.org.internal

import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.OrgAssignmentRecord
import com.memo.jooq.tables.references.ORG_ASSIGNMENT
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

enum class RecordStatus { ACTIVE, CANCELLED }

/** A half-open date range [from, to); to null is open-ended. */
data class Period(
    val from: LocalDate,
    val to: LocalDate?,
) {
    init {
        require(to == null || to.isAfter(from)) { "The end date must be after the start date." }
    }

    fun overlaps(other: Period) = (other.to == null || from < other.to) && (to == null || other.from < to)

    /** Still running after [date] (open-ended, or ending later). */
    fun runsPast(date: LocalDate) = to == null || to.isAfter(date)

    fun contains(date: LocalDate) = !from.isAfter(date) && (to == null || to.isAfter(date))

    /** Whether this period lies within [outer]. */
    fun within(outer: Period) = !from.isBefore(outer.from) && (outer.to == null || (to != null && !to.isAfter(outer.to)))
}

data class Assignment(
    val id: UUID,
    val userId: UUID,
    val positionId: UUID,
    val period: Period,
    val status: RecordStatus,
    val version: Long,
)

/** Primary assignments of people to positions (ADR-0026). Every method runs inside a tenant transaction. */
@Repository
class AssignmentStore(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    /** Serialises assignment changes of one user until commit (a user holds one primary position at a time). */
    fun lockUser(user: UUID) {
        tenantDsl.current().fetch("select pg_advisory_xact_lock(hashtextextended(?, 0))", "org.user:${tenantDsl.tenant()}:$user")
    }

    fun find(id: UUID): Assignment? =
        tenantDsl
            .current()
            .selectFrom(ORG_ASSIGNMENT)
            .where(ORG_ASSIGNMENT.ID.eq(id))
            .fetchOne()
            ?.toAssignment()

    fun ofPosition(position: UUID): List<Assignment> =
        tenantDsl
            .current()
            .selectFrom(ORG_ASSIGNMENT)
            .where(ORG_ASSIGNMENT.POSITION_ID.eq(position))
            .orderBy(ORG_ASSIGNMENT.VALID_FROM.desc(), ORG_ASSIGNMENT.ID)
            .fetch { it.toAssignment() }

    fun ofUser(user: UUID): List<Assignment> =
        tenantDsl
            .current()
            .selectFrom(ORG_ASSIGNMENT)
            .where(ORG_ASSIGNMENT.USER_ID.eq(user))
            .orderBy(ORG_ASSIGNMENT.VALID_FROM.desc(), ORG_ASSIGNMENT.ID)
            .fetch { it.toAssignment() }

    /** Active assignments of [positions] valid on [date]: the holders shown in unit and position views. */
    fun holdersOn(
        positions: Collection<UUID>,
        date: LocalDate,
    ): Map<UUID, Assignment> =
        if (positions.isEmpty()) {
            emptyMap()
        } else {
            tenantDsl
                .current()
                .selectFrom(ORG_ASSIGNMENT)
                .where(ORG_ASSIGNMENT.POSITION_ID.`in`(positions), ORG_ASSIGNMENT.STATUS.eq(RecordStatus.ACTIVE.name))
                .and(validOn(ORG_ASSIGNMENT.VALID_FROM, ORG_ASSIGNMENT.VALID_TO, date))
                .fetch { it.toAssignment() }
                .associateBy { it.positionId }
        }

    fun insert(
        user: UUID,
        position: UUID,
        period: Period,
    ): Assignment {
        val now = now()
        return tenantDsl
            .current()
            .insertInto(ORG_ASSIGNMENT)
            .set(ORG_ASSIGNMENT.ID, ids.next())
            .set(ORG_ASSIGNMENT.TENANT_ID, tenantDsl.tenant().value)
            .set(ORG_ASSIGNMENT.USER_ID, user)
            .set(ORG_ASSIGNMENT.POSITION_ID, position)
            .set(ORG_ASSIGNMENT.VALID_FROM, period.from)
            .set(ORG_ASSIGNMENT.VALID_TO, period.to)
            .set(ORG_ASSIGNMENT.CREATED_AT, now)
            .set(ORG_ASSIGNMENT.UPDATED_AT, now)
            .returning()
            .fetchSingle()
            .toAssignment()
    }

    /** Guarded update of an ACTIVE assignment on [expectedVersion]; false when it moved on or is cancelled. */
    fun update(
        id: UUID,
        expectedVersion: Long,
        period: Period,
        status: RecordStatus,
    ): Boolean =
        tenantDsl
            .current()
            .update(ORG_ASSIGNMENT)
            .set(ORG_ASSIGNMENT.VALID_FROM, period.from)
            .set(ORG_ASSIGNMENT.VALID_TO, period.to)
            .set(ORG_ASSIGNMENT.STATUS, status.name)
            .set(ORG_ASSIGNMENT.VERSION, ORG_ASSIGNMENT.VERSION.plus(1))
            .set(ORG_ASSIGNMENT.UPDATED_AT, now())
            .where(ORG_ASSIGNMENT.ID.eq(id), ORG_ASSIGNMENT.VERSION.eq(expectedVersion))
            .and(ORG_ASSIGNMENT.STATUS.eq(RecordStatus.ACTIVE.name))
            .execute() == 1

    /** Active assignments still running on or after [date] for any of [positions] (a position closing then needs none). */
    fun activeAfter(
        positions: Collection<UUID>,
        date: LocalDate,
    ): Int =
        tenantDsl.current().fetchCount(
            ORG_ASSIGNMENT,
            ORG_ASSIGNMENT.POSITION_ID.`in`(positions),
            ORG_ASSIGNMENT.STATUS.eq(RecordStatus.ACTIVE.name),
            ORG_ASSIGNMENT.VALID_TO.isNull.or(ORG_ASSIGNMENT.VALID_TO.gt(date)),
        )

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private fun OrgAssignmentRecord.toAssignment() =
        Assignment(id, userId, positionId, Period(validFrom, validTo), RecordStatus.valueOf(status!!), version!!)
}
