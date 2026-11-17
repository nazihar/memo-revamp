package com.memo.imports

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileUploads
import com.memo.imports.internal.BatchSettingsInput
import com.memo.imports.internal.BatchState
import com.memo.imports.internal.ImportBatchStore
import com.memo.imports.internal.ImportBatches
import com.memo.imports.internal.ImportIntake
import com.memo.imports.internal.ImportIntakePurpose
import com.memo.imports.internal.ImportMapping
import com.memo.imports.internal.ImportMappings
import com.memo.imports.internal.ImportSourcePurpose
import com.memo.imports.internal.MappingChange
import com.memo.imports.internal.MappingKind
import com.memo.imports.internal.MappingTarget
import com.memo.imports.internal.PathToRegister
import com.memo.imports.internal.dryrun.ImportDryRuns
import com.memo.imports.internal.run.ImportRunControl
import com.memo.imports.internal.run.RunRows
import com.memo.jooq.tables.references.IMPORTS_BATCH
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * A legacy sample to seed as an import batch (demo data, ADR-0055): the batch's settings, its workbook, its originals by
 * relative path (system files already left out, as the browser leaves them out) and how its legacy values map.
 */
class LegacySample(
    val name: String,
    val sourceCode: String,
    val timeZone: String,
    val workbookName: String,
    val workbook: ByteArray,
    val originals: Map<String, ByteArray>,
    val mappings: LegacySampleMappings,
)

/**
 * The admin's choices for a sample's legacy values: a status name (`APPROVED` … `NOT_IMPORTED`), a type's or unit's code,
 * and the legacy markings least strict first with the level's name each maps to. Values not named keep the dry run's
 * proposal (people are linked by email, or confirmed as not linked); every mapping is confirmed.
 */
data class LegacySampleMappings(
    val statuses: Map<String, String>,
    val types: Map<String, String>,
    val units: Map<String, String>,
    val classifications: List<Pair<String, String>>,
)

/** What seeding did: the batch, its state, whether it was created now, and originals the pipeline did not take. */
data class SeededImport(
    val batchId: UUID,
    val state: String,
    val created: Boolean,
    val originalsNotReceived: Int,
)

/**
 * Import batches for seeders (the demo tenant, Phase 3 E06 T10), through the same services and pipeline as the API, as
 * the user bound to the current unit of work, who must hold `memo.import` and be cleared to the sample's levels. Never
 * called from requests. Call outside a transaction, with the tenant and the user bound.
 *
 * The seeder drives the batch up to the start itself: the workbook and each original go through the upload pipeline
 * (scan, detection, encryption), the dry run runs in the seeder's thread (no job), the mappings are confirmed, the dry
 * run runs again, and the import is started without a step-up (`stepUpAt` is null in `import.batch.started`: there is no
 * session). From there the real jobs import, verify and report, as for any batch.
 */
@Service
class ImportSeeding(
    private val batches: ImportBatches,
    private val store: ImportBatchStore,
    private val intake: ImportIntake,
    private val uploads: FileUploads,
    private val dryRuns: ImportDryRuns,
    private val mappings: ImportMappings,
    private val rows: RunRows,
    private val control: ImportRunControl,
    private val transactions: TenantTransactions,
    private val tenantDsl: TenantDsl,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Seeds [sample] as a batch unless a batch of its name exists (in any state); answers the batch either way. */
    fun seedOnce(sample: LegacySample): SeededImport {
        val tenant = TenantContext.current()
        transactions.readInTenant(tenant) { existing(sample.name) }?.let {
            return SeededImport(it.id, it.state.name, false, 0)
        }
        val id =
            transactions.inTenant(tenant) {
                batches.create(BatchSettingsInput(sample.name, sample.sourceCode, sample.timeZone)).id
            }
        uploads.upload(ImportSourcePurpose.KEY, "$id:WORKBOOK", sample.workbookName, sample.workbook, "seed-$id-workbook")
        val missed = originals(id, sample.originals)
        dryRun(id)
        confirm(id, sample.mappings)
        dryRun(id)
        return SeededImport(id, start(id).name, true, missed)
    }

    /** Registers and uploads each original as the browser would; returns how many the pipeline did not take. */
    private fun originals(
        batchId: UUID,
        originals: Map<String, ByteArray>,
    ): Int {
        val tenant = TenantContext.current()
        val entries =
            transactions.inTenant(tenant) {
                intake.register(batchId, originals.map { (path, bytes) -> PathToRegister(path, bytes.size.toLong()) })
            }
        return entries.count { entry ->
            val bytes = originals.getValue(entry.path)
            !uploaded(entry.id, entry.path.substringAfterLast('/'), bytes)
        }
    }

    /** One original through the pipeline; false when refused (or not checkable now: no Tika server for a `.doc`). */
    @Suppress("TooGenericExceptionCaught") // any refusal leaves the path as the pipeline left it; the dry run reports it
    private fun uploaded(
        intakeId: UUID,
        name: String,
        bytes: ByteArray,
    ): Boolean =
        try {
            uploads.upload(ImportIntakePurpose.KEY, intakeId.toString(), name, bytes, "imp-$intakeId-${bytes.size}")
            true
        } catch (e: RuntimeException) {
            log.info("Seeded import: the original {} was not taken ({})", name, e.message)
            false
        }

    /** A dry run run here, not by its job. */
    private fun dryRun(batchId: UUID) {
        transactions.inTenant(TenantContext.current()) { dryRuns.request(batchId, scheduleJob = false) }
        dryRuns.run(batchId)
    }

    /** Every mapping the dry run proposed, confirmed with the sample's choices where it names one. */
    private fun confirm(
        batchId: UUID,
        choices: LegacySampleMappings,
    ) {
        val tenant = TenantContext.current()
        val targets = transactions.readInTenant(tenant) { mappings.targets(null, emptyList()) }
        MappingKind.entries.forEach { kind ->
            val current = transactions.readInTenant(tenant) { mappings.list(batchId, kind) }
            if (current.isEmpty()) return@forEach
            val changes = current.map { MappingChange(it.legacyValue, target(it, choices, targets), order(it, choices), true) }
            transactions.inTenant(tenant) {
                mappings.save(batchId, checkNotNull(store.find(batchId)).version, kind, changes)
            }
        }
    }

    private fun target(
        mapping: ImportMapping,
        choices: LegacySampleMappings,
        targets: List<MappingTarget>,
    ): String? {
        fun byDetail(code: String?) = targets.firstOrNull { it.kind == mapping.kind && it.detail == code }?.id
        val value = mapping.legacyValue
        val chosen =
            when (mapping.kind) {
                MappingKind.STATUS -> choices.statuses[value]
                MappingKind.TYPE -> choices.types[value]?.let(::byDetail)
                MappingKind.UNIT -> choices.units[value]?.let(::byDetail)
                MappingKind.CLASSIFICATION -> level(choices, value, targets)
                MappingKind.PERSON -> null
            }
        return chosen ?: mapping.target
    }

    /** The newest batch named [name], in any state (seeding fills gaps only). */
    private fun existing(name: String) =
        tenantDsl
            .current()
            .select(IMPORTS_BATCH.ID)
            .from(IMPORTS_BATCH)
            .where(IMPORTS_BATCH.NAME.eq(name))
            .orderBy(IMPORTS_BATCH.CREATED_AT.desc())
            .limit(1)
            .fetchOne { it.value1() }
            ?.let(store::find)

    /** The level a legacy marking maps to by the sample's choice, by name. */
    private fun level(
        choices: LegacySampleMappings,
        value: String,
        targets: List<MappingTarget>,
    ): String? {
        val name = choices.classifications.firstOrNull { it.first == value }?.second
        return targets.firstOrNull { it.kind == MappingKind.CLASSIFICATION && it.label == name }?.id
    }

    private fun order(
        mapping: ImportMapping,
        choices: LegacySampleMappings,
    ): Int? {
        if (mapping.kind != MappingKind.CLASSIFICATION) return null
        val index = choices.classifications.indexOfFirst { it.first == mapping.legacyValue }
        return if (index >= 0) index + 1 else mapping.legacyOrder
    }

    /** VALIDATED → IMPORTING with the READY rows as they stand; the `imports-run` job takes it from there. */
    private fun start(batchId: UUID): BatchState =
        transactions.inTenant(TenantContext.current()) {
            val batch = checkNotNull(store.find(batchId))
            if (batch.state != BatchState.VALIDATED) {
                batch.state
            } else {
                control.start(batchId, batch.version, rows.readyCount(batchId), null).state
            }
        }
}
