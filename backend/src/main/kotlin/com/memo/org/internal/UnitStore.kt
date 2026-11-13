package com.memo.org.internal

import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.OrgUnitRecord
import com.memo.jooq.tables.references.ORG_POSITION
import com.memo.jooq.tables.references.ORG_UNIT
import org.jooq.Condition
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class OrgUnit(
    val id: UUID,
    val legalEntityId: UUID,
    val parentId: UUID?,
    /** Root first, without the unit itself. */
    val ancestors: List<UUID>,
    val code: String,
    val name: String,
    val validFrom: LocalDate,
    val validTo: LocalDate?,
    val version: Long,
) {
    fun openOn(date: LocalDate) = !validFrom.isAfter(date) && (validTo == null || validTo.isAfter(date))
}

/** A [from, to) range is valid on [date]. */
fun validOn(
    from: Field<LocalDate?>,
    to: Field<LocalDate?>,
    date: LocalDate,
): Condition = from.le(date).and(to.isNull.or(to.gt(date)))

/** The units tree (ADR-0026). Every method runs inside a tenant transaction. */
@Repository
class UnitStore(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    /**
     * Serialises every change of the tenant's tree (create, move, close) until commit, so concurrent moves cannot build a
     * cycle between them.
     */
    fun lockTree() {
        tenantDsl.current().fetch("select pg_advisory_xact_lock(hashtextextended(?, 0))", "org.tree:${tenantDsl.tenant()}")
    }

    fun units(): List<OrgUnit> =
        tenantDsl
            .current()
            .selectFrom(ORG_UNIT)
            .orderBy(DSL.lower(ORG_UNIT.NAME), ORG_UNIT.ID)
            .fetch { it.toUnit() }

    /** The names of the units of [ids] that exist. */
    fun names(ids: Collection<UUID>): Map<UUID, String> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            tenantDsl
                .current()
                .select(ORG_UNIT.ID, ORG_UNIT.NAME)
                .from(ORG_UNIT)
                .where(ORG_UNIT.ID.`in`(ids))
                .fetch()
                .associate { checkNotNull(it.value1()) to checkNotNull(it.value2()) }
        }

    fun unit(id: UUID): OrgUnit? =
        tenantDsl
            .current()
            .selectFrom(ORG_UNIT)
            .where(ORG_UNIT.ID.eq(id))
            .fetchOne()
            ?.toUnit()

    fun insertUnit(
        legalEntityId: UUID,
        parent: OrgUnit?,
        code: String,
        name: String,
        validFrom: LocalDate,
    ): OrgUnit {
        val now = now()
        val ancestors = parent?.let { it.ancestors + it.id }.orEmpty()
        return tenantDsl
            .current()
            .insertInto(ORG_UNIT)
            .set(ORG_UNIT.ID, ids.next())
            .set(ORG_UNIT.TENANT_ID, tenantDsl.tenant().value)
            .set(ORG_UNIT.LEGAL_ENTITY_ID, legalEntityId)
            .set(ORG_UNIT.PARENT_ID, parent?.id)
            .set(ORG_UNIT.ANCESTORS, ancestors.toTypedArray<UUID?>())
            .set(ORG_UNIT.CODE, code)
            .set(ORG_UNIT.NAME, name)
            .set(ORG_UNIT.VALID_FROM, validFrom)
            .set(ORG_UNIT.CREATED_AT, now)
            .set(ORG_UNIT.UPDATED_AT, now)
            .returning()
            .fetchSingle()
            .toUnit()
    }

    fun updateUnit(
        id: UUID,
        expectedVersion: Long,
        code: String,
        name: String,
        validTo: LocalDate?,
    ): Boolean =
        tenantDsl
            .current()
            .update(ORG_UNIT)
            .set(ORG_UNIT.CODE, code)
            .set(ORG_UNIT.NAME, name)
            .set(ORG_UNIT.VALID_TO, validTo)
            .set(ORG_UNIT.VERSION, ORG_UNIT.VERSION.plus(1))
            .set(ORG_UNIT.UPDATED_AT, now())
            .where(ORG_UNIT.ID.eq(id), ORG_UNIT.VERSION.eq(expectedVersion))
            .execute() == 1

    /**
     * Moves [unit] under [parent] and rewrites the ancestors of its whole subtree in one statement: every descendant
     * keeps the part of its path from [unit] down and gets the new parent's path in front.
     */
    fun move(
        unit: OrgUnit,
        expectedVersion: Long,
        parent: OrgUnit,
    ): Boolean {
        val dsl = tenantDsl.current()
        val prefix = (parent.ancestors + parent.id).toTypedArray<UUID?>()
        val moved =
            dsl
                .update(ORG_UNIT)
                .set(ORG_UNIT.PARENT_ID, parent.id)
                .set(ORG_UNIT.ANCESTORS, prefix)
                .set(ORG_UNIT.VERSION, ORG_UNIT.VERSION.plus(1))
                .set(ORG_UNIT.UPDATED_AT, now())
                .where(ORG_UNIT.ID.eq(unit.id), ORG_UNIT.VERSION.eq(expectedVersion))
                .execute() == 1
        if (moved) {
            dsl.execute(
                """
                update org_unit set ancestors = ?::uuid[] || ancestors[array_position(ancestors, ?::uuid):]
                where ancestors @> array[?::uuid]
                """.trimIndent(),
                prefix,
                unit.id,
                unit.id,
            )
        }
        return moved
    }

    /** Children and positions of [unit] still open on or after [date] (a unit closing on [date] must have none). */
    fun openContentsAfter(
        unit: UUID,
        date: LocalDate,
    ): Int {
        val dsl = tenantDsl.current()
        val children = dsl.fetchCount(ORG_UNIT, ORG_UNIT.PARENT_ID.eq(unit), ORG_UNIT.VALID_TO.isNull.or(ORG_UNIT.VALID_TO.gt(date)))
        val positions =
            dsl.fetchCount(ORG_POSITION, ORG_POSITION.UNIT_ID.eq(unit), ORG_POSITION.VALID_TO.isNull.or(ORG_POSITION.VALID_TO.gt(date)))
        return children + positions
    }

    fun codeTaken(
        code: String,
        except: UUID? = null,
    ): Boolean =
        tenantDsl.current().fetchExists(
            ORG_UNIT,
            DSL.lower(ORG_UNIT.CODE).eq(code.lowercase()),
            ORG_UNIT.VALID_TO.isNull,
            except?.let { ORG_UNIT.ID.ne(it) } ?: DSL.noCondition(),
        )

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private fun OrgUnitRecord.toUnit() =
        OrgUnit(
            id,
            legalEntityId,
            parentId,
            ancestors?.filterNotNull().orEmpty(),
            code,
            name,
            validFrom,
            validTo,
            version!!,
        )
}
