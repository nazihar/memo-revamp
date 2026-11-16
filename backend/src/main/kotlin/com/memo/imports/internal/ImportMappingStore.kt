package com.memo.imports.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.ImportsMappingRecord
import com.memo.jooq.tables.references.IMPORTS_MAPPING
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** What a legacy value is mapped to (ADR-0055, design E06 §6). */
enum class MappingKind { STATUS, TYPE, CLASSIFICATION, UNIT, PERSON }

/**
 * The targets of a STATUS mapping: the final statuses a legacy memo is imported in, or NOT_IMPORTED for a status that
 * was still in flight (its rows are refused with `IN_FLIGHT_NOT_IMPORTED`).
 */
enum class StatusTarget { APPROVED, REJECTED, WITHDRAWN, CANCELLED, CLOSED, NOT_IMPORTED }

/**
 * One legacy value of a batch and what it maps to: a [StatusTarget] name, or the id of a memo type, classification level,
 * unit or user. [proposed] says the target is the dry run's proposal, untouched; [legacyOrder] is the admin's order of
 * the legacy classification markings (1 = least strict). A row using it can be READY only once it is confirmed.
 */
data class ImportMapping(
    val kind: MappingKind,
    val legacyValue: String,
    val legacyOrder: Int?,
    val target: String?,
    val proposed: Boolean,
    val confirmedBy: UUID?,
    val confirmedAt: Instant?,
    val rowCount: Int,
) {
    val confirmed: Boolean get() = confirmedBy != null
}

/** A mapping as the admin saves it. */
data class MappingChange(
    val legacyValue: String,
    val target: String?,
    val legacyOrder: Int?,
    val confirmed: Boolean,
)

/** What a dry run found for a legacy value: how many rows use it, and its proposal when it has none yet. */
data class FoundValue(
    val rowCount: Int,
    val proposal: String?,
    val proposedOrder: Int? = null,
)

/** `imports_mapping`, inside a tenant transaction. */
@Repository
class ImportMappingStore(
    private val tenantDsl: TenantDsl,
    private val clock: Clock,
) {
    /** The batch's mappings (of [kind] when given), by kind and legacy value. */
    fun all(
        batchId: UUID,
        kind: MappingKind? = null,
    ): List<ImportMapping> =
        tenantDsl
            .current()
            .selectFrom(IMPORTS_MAPPING)
            .where(IMPORTS_MAPPING.BATCH_ID.eq(batchId))
            .and(
                kind?.let { IMPORTS_MAPPING.KIND.eq(it.name) } ?: org.jooq.impl.DSL
                    .noCondition(),
            ).orderBy(IMPORTS_MAPPING.KIND, IMPORTS_MAPPING.LEGACY_VALUE)
            .fetch(::mapping)

    /**
     * After a dry run read the sheets: each value [found] gets its row count; a new one is added with its proposal; an
     * existing one without a target takes the proposal unless it was confirmed; confirmed ones are kept (with no rows when
     * the sheets no longer use them), and unconfirmed ones the sheets no longer use are dropped.
     */
    fun sync(
        batchId: UUID,
        kind: MappingKind,
        found: Map<String, FoundValue>,
    ) {
        val existing = all(batchId, kind).associateBy { it.legacyValue }
        val dsl = tenantDsl.current()
        val m = IMPORTS_MAPPING
        val tenant = tenantDsl.tenant().value
        val stale = existing.values.filter { it.legacyValue !in found && !it.confirmed }.map { it.legacyValue }
        if (stale.isNotEmpty()) {
            dsl.deleteFrom(m).where(m.BATCH_ID.eq(batchId), m.KIND.eq(kind.name), m.LEGACY_VALUE.`in`(stale)).execute()
        }
        val unused = existing.values.filter { it.legacyValue !in found && it.confirmed && it.rowCount != 0 }.map { it.legacyValue }
        if (unused.isNotEmpty()) {
            dsl
                .update(m)
                .set(m.ROW_COUNT, 0)
                .where(m.BATCH_ID.eq(batchId), m.KIND.eq(kind.name), m.LEGACY_VALUE.`in`(unused))
                .execute()
        }
        val writes =
            found.mapNotNull { (value, f) ->
                val known = existing[value]
                when {
                    known == null -> {
                        dsl
                            .insertInto(m)
                            .set(m.TENANT_ID, tenant)
                            .set(m.BATCH_ID, batchId)
                            .set(m.KIND, kind.name)
                            .set(m.LEGACY_VALUE, value)
                            .set(m.LEGACY_ORDER, f.proposedOrder.takeIf { kind == MappingKind.CLASSIFICATION })
                            .set(m.TARGET, f.proposal)
                            .set(m.PROPOSED, f.proposal != null)
                            .set(m.ROW_COUNT, f.rowCount)
                    }

                    known.target == null && !known.confirmed && f.proposal != null -> {
                        dsl
                            .update(m)
                            .set(m.TARGET, f.proposal)
                            .set(m.PROPOSED, true)
                            .set(m.LEGACY_ORDER, known.legacyOrder ?: f.proposedOrder.takeIf { kind == MappingKind.CLASSIFICATION })
                            .set(m.ROW_COUNT, f.rowCount)
                            .where(m.BATCH_ID.eq(batchId), m.KIND.eq(kind.name), m.LEGACY_VALUE.eq(value))
                    }

                    known.rowCount != f.rowCount -> {
                        dsl
                            .update(m)
                            .set(m.ROW_COUNT, f.rowCount)
                            .where(m.BATCH_ID.eq(batchId), m.KIND.eq(kind.name), m.LEGACY_VALUE.eq(value))
                    }

                    else -> {
                        null
                    }
                }
            }
        writes.chunked(BATCH).forEach { dsl.batch(it).execute() }
    }

    /** Saves [changes] (the caller checked them); a changed target is no longer the proposal. Confirmation is by [user]. */
    fun apply(
        batchId: UUID,
        kind: MappingKind,
        changes: List<MappingChange>,
        user: UUID,
    ) {
        val existing = all(batchId, kind).associateBy { it.legacyValue }
        val dsl = tenantDsl.current()
        val m = IMPORTS_MAPPING
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        val writes =
            changes.map { change ->
                val known = existing.getValue(change.legacyValue)
                val stillProposed = known.proposed && known.target == change.target
                dsl
                    .update(m)
                    .set(m.TARGET, change.target)
                    .set(m.PROPOSED, stillProposed)
                    .set(m.LEGACY_ORDER, change.legacyOrder.takeIf { kind == MappingKind.CLASSIFICATION })
                    .set(m.CONFIRMED_BY, user.takeIf { change.confirmed })
                    .set(m.CONFIRMED_AT, now.takeIf { change.confirmed })
                    .where(m.BATCH_ID.eq(batchId), m.KIND.eq(kind.name), m.LEGACY_VALUE.eq(change.legacyValue))
            }
        writes.chunked(BATCH).forEach { dsl.batch(it).execute() }
    }

    private fun mapping(r: ImportsMappingRecord): ImportMapping =
        ImportMapping(
            kind = MappingKind.valueOf(r.kind),
            legacyValue = r.legacyValue,
            legacyOrder = r.legacyOrder,
            target = r.target,
            proposed = r.proposed == true,
            confirmedBy = r.confirmedBy,
            confirmedAt = r.confirmedAt?.toInstant(),
            rowCount = r.rowCount ?: 0,
        )

    private companion object {
        const val BATCH = 500
    }
}
