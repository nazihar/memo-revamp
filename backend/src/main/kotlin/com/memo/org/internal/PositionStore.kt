package com.memo.org.internal

import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.OrgPositionRecord
import com.memo.jooq.tables.references.ORG_POSITION
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class OrgPosition(
    val id: UUID,
    val unitId: UUID,
    val code: String?,
    val title: String,
    val head: Boolean,
    val validFrom: LocalDate,
    val validTo: LocalDate?,
    val version: Long,
) {
    fun openOn(date: LocalDate) = !validFrom.isAfter(date) && (validTo == null || validTo.isAfter(date))
}

/** Positions (ADR-0026). Every method runs inside a tenant transaction. */
@Repository
class PositionStore(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    fun find(id: UUID): OrgPosition? =
        tenantDsl
            .current()
            .selectFrom(ORG_POSITION)
            .where(ORG_POSITION.ID.eq(id))
            .fetchOne()
            ?.toPosition()

    /** The position, its row locked until commit (serialises assignments and reporting lines of one seat). */
    fun lock(id: UUID): OrgPosition? =
        tenantDsl
            .current()
            .selectFrom(ORG_POSITION)
            .where(ORG_POSITION.ID.eq(id))
            .forUpdate()
            .fetchOne()
            ?.toPosition()

    fun ofUnit(unit: UUID): List<OrgPosition> =
        tenantDsl
            .current()
            .selectFrom(ORG_POSITION)
            .where(ORG_POSITION.UNIT_ID.eq(unit))
            .orderBy(ORG_POSITION.HEAD.desc(), DSL.lower(ORG_POSITION.TITLE), ORG_POSITION.ID)
            .fetch { it.toPosition() }

    fun byIds(ids: Collection<UUID>): Map<UUID, OrgPosition> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            tenantDsl
                .current()
                .selectFrom(ORG_POSITION)
                .where(ORG_POSITION.ID.`in`(ids))
                .fetch { it.toPosition() }
                .associateBy { it.id }
        }

    fun insert(
        unit: UUID,
        code: String?,
        title: String,
        head: Boolean,
        validFrom: LocalDate,
    ): OrgPosition {
        val now = now()
        return tenantDsl
            .current()
            .insertInto(ORG_POSITION)
            .set(ORG_POSITION.ID, ids.next())
            .set(ORG_POSITION.TENANT_ID, tenantDsl.tenant().value)
            .set(ORG_POSITION.UNIT_ID, unit)
            .set(ORG_POSITION.CODE, code)
            .set(ORG_POSITION.TITLE, title)
            .set(ORG_POSITION.HEAD, head)
            .set(ORG_POSITION.VALID_FROM, validFrom)
            .set(ORG_POSITION.CREATED_AT, now)
            .set(ORG_POSITION.UPDATED_AT, now)
            .returning()
            .fetchSingle()
            .toPosition()
    }

    /** Guarded update on [expectedVersion]; false when the row moved on. */
    fun update(
        expectedVersion: Long,
        changed: OrgPosition,
    ): Boolean =
        tenantDsl
            .current()
            .update(ORG_POSITION)
            .set(ORG_POSITION.CODE, changed.code)
            .set(ORG_POSITION.TITLE, changed.title)
            .set(ORG_POSITION.HEAD, changed.head)
            .set(ORG_POSITION.VALID_TO, changed.validTo)
            .set(ORG_POSITION.VERSION, ORG_POSITION.VERSION.plus(1))
            .set(ORG_POSITION.UPDATED_AT, now())
            .where(ORG_POSITION.ID.eq(changed.id), ORG_POSITION.VERSION.eq(expectedVersion))
            .execute() == 1

    /** Another open head position of [unit] (at most one per unit). */
    fun headTaken(
        unit: UUID,
        except: UUID?,
    ): Boolean =
        tenantDsl.current().fetchExists(
            ORG_POSITION,
            ORG_POSITION.UNIT_ID.eq(unit),
            ORG_POSITION.HEAD.isTrue,
            ORG_POSITION.VALID_TO.isNull,
            except?.let { ORG_POSITION.ID.ne(it) } ?: DSL.noCondition(),
        )

    fun codeTaken(
        code: String,
        except: UUID?,
    ): Boolean =
        tenantDsl.current().fetchExists(
            ORG_POSITION,
            DSL.lower(ORG_POSITION.CODE).eq(code.lowercase()),
            ORG_POSITION.VALID_TO.isNull,
            except?.let { ORG_POSITION.ID.ne(it) } ?: DSL.noCondition(),
        )

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private fun OrgPositionRecord.toPosition() = OrgPosition(id, unitId, code, title, head!!, validFrom, validTo, version!!)
}
