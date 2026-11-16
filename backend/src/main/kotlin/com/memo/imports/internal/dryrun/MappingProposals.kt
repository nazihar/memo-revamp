package com.memo.imports.internal.dryrun

import com.memo.common.authz.Clearances
import com.memo.common.tenant.TenantDsl
import com.memo.identity.UserSummaries
import com.memo.imports.internal.FoundValue
import com.memo.imports.internal.ImportMappingStore
import com.memo.imports.internal.MappingKind
import com.memo.imports.internal.StatusTarget
import com.memo.jooq.tables.references.IMPORTS_ROW
import com.memo.jooq.tables.references.IMPORTS_ROW_APPROVAL
import com.memo.jooq.tables.references.IMPORTS_ROW_FILE
import com.memo.memo.LegacyMemos
import com.memo.org.OrgUnitDirectory
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import java.util.Locale
import java.util.UUID

/** How a person is identified in the sheets: an email, else a legacy id (design E06 §6.4). A name alone is no key. */
object PersonKeys {
    const val EMAIL = "email:"
    const val ID = "id:"

    fun of(
        email: String?,
        legacyId: String?,
    ): String? =
        when {
            !email.isNullOrBlank() -> EMAIL + email.trim().lowercase(Locale.ROOT)
            !legacyId.isNullOrBlank() -> ID + legacyId.trim()
            else -> null
        }
}

/** The distinct legacy values of a batch's staged rows, with how many rows use each (set-based reads of staging). */
@Repository
class StagedValues(
    private val tenantDsl: TenantDsl,
) {
    /** Values of the Memos column [column], with counts. */
    fun memoColumn(
        batchId: UUID,
        column: String,
    ): Map<String, Int> {
        val value = cell(column)
        return tenantDsl
            .current()
            .select(value, DSL.count())
            .from(IMPORTS_ROW)
            .where(IMPORTS_ROW.BATCH_ID.eq(batchId), value.isNotNull)
            .groupBy(value)
            .fetch { it.value1()!! to (it.value2() ?: 0) }
            .toMap()
    }

    /** Classification values of Files rows, with counts. */
    fun fileClassifications(batchId: UUID): Map<String, Int> =
        tenantDsl
            .current()
            .select(IMPORTS_ROW_FILE.CLASSIFICATION, DSL.count())
            .from(IMPORTS_ROW_FILE)
            .where(IMPORTS_ROW_FILE.BATCH_ID.eq(batchId), IMPORTS_ROW_FILE.CLASSIFICATION.isNotNull)
            .groupBy(IMPORTS_ROW_FILE.CLASSIFICATION)
            .fetch { it.value1()!! to (it.value2() ?: 0) }
            .toMap()

    /** People keys of initiators and approvers, with counts. */
    fun people(batchId: UUID): Map<String, Int> {
        val email = cell(MemoColumns.INITIATOR_EMAIL)
        val id = cell(MemoColumns.INITIATOR_ID)
        val initiators =
            tenantDsl
                .current()
                .select(email, id, DSL.count())
                .from(IMPORTS_ROW)
                .where(IMPORTS_ROW.BATCH_ID.eq(batchId), email.isNotNull.or(id.isNotNull))
                .groupBy(email, id)
                .fetch { Triple(it.value1(), it.value2(), it.value3() ?: 0) }
        val a = IMPORTS_ROW_APPROVAL
        val approvers =
            tenantDsl
                .current()
                .select(a.EMAIL, a.LEGACY_ID, DSL.count())
                .from(a)
                .where(a.BATCH_ID.eq(batchId), a.EMAIL.isNotNull.or(a.LEGACY_ID.isNotNull))
                .groupBy(a.EMAIL, a.LEGACY_ID)
                .fetch { Triple(it.value1(), it.value2(), it.value3() ?: 0) }
        return (initiators + approvers)
            .mapNotNull { (e, i, count) -> PersonKeys.of(e, i)?.let { it to count } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.sum() }
    }

    private fun cell(column: String): Field<String?> =
        DSL.field("{0} -> {1} ->> {2}", String::class.java, IMPORTS_ROW.DATA, DSL.inline(StagingStore.CELLS), DSL.inline(column))
}

/**
 * Proposes mappings (design E06 §5.2 step 3, §6): collects each kind's distinct legacy values with their row counts and
 * proposes a target for new ones, keeping confirmed ones. A status equal to one of ours, a type's code or name, a unit's
 * code, a level's name and a user's email (any status: a leaver links too) are proposed; a legacy id never is. Staging
 * only; inside a tenant transaction.
 */
@Component
class MappingProposals(
    private val values: StagedValues,
    private val mappings: ImportMappingStore,
    private val legacy: LegacyMemos,
    private val units: OrgUnitDirectory,
    private val clearances: Clearances,
    private val users: UserSummaries,
) {
    fun propose(batchId: UUID) {
        val statuses = StatusTarget.entries.associateBy { it.name }
        sync(batchId, MappingKind.STATUS, values.memoColumn(batchId, MemoColumns.STATUS)) { statuses[norm(it)]?.name }

        val types = legacy.types()
        val byCode = types.associateBy { it.code.uppercase(Locale.ROOT) }
        val byName = types.associateBy { it.name.uppercase(Locale.ROOT) }
        sync(batchId, MappingKind.TYPE, values.memoColumn(batchId, MemoColumns.MEMO_TYPE)) {
            val key = it.trim().uppercase(Locale.ROOT)
            (byCode[key] ?: byName[key])?.id?.toString()
        }

        val unitsByCode = units.units(units.allUnits()).values.associateBy { it.code.uppercase(Locale.ROOT) }
        sync(batchId, MappingKind.UNIT, values.memoColumn(batchId, MemoColumns.ORIGIN_UNIT)) {
            unitsByCode[it.trim().uppercase(Locale.ROOT)]?.id?.toString()
        }

        classifications(batchId)
        people(batchId)
    }

    private fun classifications(batchId: UUID) {
        val levels = clearances.levels().associateBy { it.name.trim().uppercase(Locale.ROOT) }
        val found =
            (values.memoColumn(batchId, MemoColumns.CLASSIFICATION).toList() + values.fileClassifications(batchId).toList())
                .filter { it.first.length <= MAX_VALUE }
                .groupBy({ it.first }, { it.second })
                .mapValues { (value, counts) ->
                    val level = levels[value.trim().uppercase(Locale.ROOT)]
                    FoundValue(counts.sum(), level?.id?.toString(), level?.let { it.rank + 1 })
                }
        mappings.sync(batchId, MappingKind.CLASSIFICATION, found)
    }

    private fun people(batchId: UUID) {
        val found = values.people(batchId)
        val emails = found.keys.filter { it.startsWith(PersonKeys.EMAIL) }.map { it.removePrefix(PersonKeys.EMAIL) }
        val known = users.byEmails(emails)
        sync(batchId, MappingKind.PERSON, found) { key ->
            if (key.startsWith(PersonKeys.EMAIL)) known[key.removePrefix(PersonKeys.EMAIL)]?.id?.toString() else null
        }
    }

    private fun sync(
        batchId: UUID,
        kind: MappingKind,
        counts: Map<String, Int>,
        proposal: (String) -> String?,
    ) = mappings.sync(
        batchId,
        kind,
        counts.filterKeys { it.length <= MAX_VALUE }.mapValues { (value, count) -> FoundValue(count, proposal(value)) },
    )

    private fun norm(value: String): String = value.trim().uppercase(Locale.ROOT).replace(SEPARATORS, "_")

    private companion object {
        /** `imports_mapping.legacy_value`'s bound; longer values are refused as too long when the row is validated. */
        const val MAX_VALUE = 500
        val SEPARATORS = Regex("[\\s-]+")
    }
}
