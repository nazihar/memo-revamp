package com.memo.imports.internal.report

import com.memo.common.authz.Clearances
import com.memo.common.tenant.TenantDsl
import com.memo.files.RenditionKind
import com.memo.files.RenditionState
import com.memo.identity.UserSummaries
import com.memo.imports.internal.ImportBatch
import com.memo.imports.internal.ImportMappingStore
import com.memo.imports.internal.ImportMappings
import com.memo.imports.internal.dryrun.ProblemStage
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Gathers what the report says (design E06 §8): the header (tenant, batch, who created, started and finished it and when,
 * the step-up, each source with its SHA-256, the time zone, each mapping kind's hash, the default classification, the
 * prefix and the software), every total with the balance, completeness and validity, and the originals named one by one.
 * Inside a tenant transaction.
 */
@Component
class ReportFactsBuilder(
    private val queries: ReportQueries,
    private val mappings: ImportMappingStore,
    private val people: UserSummaries,
    private val clearances: Clearances,
    private val tenantDsl: TenantDsl,
    private val json: JsonMapper,
) {
    /** [versions] are the imported originals' stored versions, [states] their renditions; [sources] the sources as kept. */
    fun of(
        batch: ImportBatch,
        versions: List<UUID>,
        states: Map<UUID, Map<RenditionKind, RenditionState>>,
        sources: List<ReportPart>,
    ): ReportFacts {
        val verification = batch.end.verification?.let { json.readValue(it, VerificationCounts::class.java) } ?: VerificationCounts()
        val totals =
            ReportTotals(
                rows = queries.rows(batch.id),
                files = queries.files(batch.id),
                originals = queries.originals(batch.id),
                renditions = ReportQueries.renditions(versions, states),
                fixity =
                    FixityTotals(
                        importedOriginals = versions.size,
                        verified = verification.originals,
                        mismatches = verification.mismatches,
                        verifyProblems = verification.problems - verification.mismatches,
                        promotionMismatches = queries.count(batch.id, ProblemStage.IMPORT, "FILE_HASH_MISMATCH"),
                    ),
                storedNotBound = queries.storedNotBound(batch.id).size,
                problems = queries.problems(batch.id),
            )
        val originals = queries.importedOriginals(batch.id).associateBy { it.versionId }
        val failed =
            versions.mapNotNull { version ->
                val kinds =
                    states[version]
                        .orEmpty()
                        .filterValues { it == RenditionState.FAILED }
                        .keys
                        .map { it.name }
                        .sorted()
                originals[version]?.takeIf { kinds.isNotEmpty() }?.let { FailedRendition(it.rowNumber, it.path, version, kinds) }
            }
        return ReportFacts(
            header = header(batch, sources),
            totals = totals,
            balance = ReportBalance.of(totals),
            storedNotBound = queries.storedNotBound(batch.id),
            failedRenditions = failed.sortedBy { it.rowNumber },
        )
    }

    private fun header(
        batch: ImportBatch,
        sources: List<ReportPart>,
    ): List<Pair<String, String>> {
        val run = batch.run
        val names = people.of(listOfNotNull(batch.createdBy, run.startedBy)).mapValues { it.value.displayName }

        fun who(id: UUID?) = id?.let { "${names[it] ?: "unknown"} ($it)" } ?: "—"
        val kinds = mappings.all(batch.id).groupBy { it.kind }
        val level = batch.settings.defaultClassificationId?.let(clearances::level)
        return listOf(
            "Tenant" to tenantDsl.tenant().toString(),
            "Batch" to "${batch.settings.name} (${batch.id})",
            "Source code" to batch.settings.sourceCode,
            "Created" to "${who(batch.createdBy)}, ${batch.createdAt}",
            "Started" to "${who(run.startedBy)}, ${run.startedAt ?: "—"}",
            "Step-up proven" to (run.stepUpAt?.toString() ?: "—"),
            "Finished" to (run.finishedAt?.toString() ?: "—"),
            "Verified" to (batch.end.verifiedAt?.toString() ?: "—"),
            "Time zone (UTC times elsewhere)" to batch.settings.timeZone.id,
        ) +
            sources.map { "Source ${it.kind.removePrefix("SOURCE_")}" to "${it.name}, ${it.size} bytes, SHA-256 ${it.sha256}" } +
            kinds.map { (kind, list) -> "Mappings $kind" to "${list.size} values, SHA-256 ${ImportMappings.hash(list)}" } +
            listOf(
                "Default classification" to (level?.name ?: "none"),
                "Number prefix" to (batch.settings.numberPrefix ?: "none"),
                "Software" to "memo ${ReportFactsBuilder::class.java.`package`?.implementationVersion ?: "(development build)"}",
            )
    }
}
