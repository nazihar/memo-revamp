package com.memo.imports.internal.report

import com.memo.memo.ReportDocument
import com.memo.memo.ReportSection
import java.util.UUID

/** An original whose rendition failed (an encrypted PowerPoint shows here, ADR-0055): its path and the kinds that failed. */
data class FailedRendition(
    val rowNumber: Int,
    val path: String,
    val versionId: UUID,
    val kinds: List<String>,
)

/** What the report says beyond its counts: the header lines and the originals it names one by one. */
data class ReportFacts(
    /** Label and value, in order (design E06 §8 "Header"). */
    val header: List<Pair<String, String>>,
    val totals: ReportTotals,
    val balance: ReportBalance,
    val storedNotBound: List<StoredOriginal>,
    val failedRenditions: List<FailedRendition>,
)

/**
 * The summary PDF's content (design E06 §8, part 1; English only): the header, BALANCED or UNBALANCED with each failing
 * line, complete and valid, every total, the originals stored but not bound and those whose renditions failed (at most
 * [MAX_LISTED] each; the workbook has them all), and the problems by code. Never a subject or a remark.
 */
object ReportSummary {
    const val MAX_LISTED = 200

    fun document(
        batchName: String,
        facts: ReportFacts,
    ): ReportDocument {
        val t = facts.totals
        val b = facts.balance
        val result =
            ReportSection(
                "Result",
                listOf(
                    if (b.balanced) "BALANCED" else "UNBALANCED: ${b.failing.joinToString(", ")}",
                    "Complete: ${yes(b.complete)}. Valid: ${yes(b.valid)}.",
                ),
            )
        val sections =
            listOf(ReportSection("Batch", lines = facts.header), result) +
                totals(t) +
                listed(facts) +
                ReportSection(
                    "Problems by code",
                    lines = t.problems.flatMap { (level, codes) -> codes.map { (code, n) -> "$level $code" to n.toString() } },
                )
        return ReportDocument("Reconciliation report", "Legacy import · $batchName", "Reconciliation report", sections)
    }

    private fun totals(t: ReportTotals): List<ReportSection> = rowSections(t) + originalSections(t)

    private fun rowSections(t: ReportTotals): List<ReportSection> =
        listOf(
            ReportSection(
                "Rows",
                listOf("Read = imported + skipped + rejected + failed + not started."),
                listOf(
                    "Read" to "${t.rows.read}",
                    "Imported" to "${t.rows.imported}",
                    "Skipped (imported before)" to "${t.rows.skipped}",
                    "Rejected" to "${t.rows.rejected}",
                    "Failed" to "${t.rows.failed}",
                    "Not started" to "${t.rows.notStarted}",
                ) + t.rows.rejectedByCode.map { (code, n) -> "Rejected $code" to "$n" },
            ),
            ReportSection(
                "Files listed",
                listOf("Files rows = matched + not received + ambiguous."),
                listOf(
                    "Files rows" to "${t.files.listed}",
                    "Matched" to "${t.files.matched}",
                    "Not received" to "${t.files.notReceived}",
                    "Ambiguous" to "${t.files.ambiguous}",
                ),
            ),
        )

    private fun originalSections(t: ReportTotals): List<ReportSection> {
        val o = t.originals
        return listOf(
            ReportSection(
                "Originals received",
                listOf("Registered = stored + refused + never uploaded; stored = imported + held back + unreferenced."),
                listOf(
                    "Registered" to bytes(o.registered),
                    "Stored" to bytes(o.stored),
                    "Refused" to bytes(o.refused),
                    "Never uploaded" to bytes(o.neverUploaded),
                    "Imported" to bytes(o.imported),
                    "Held back" to bytes(o.heldBack),
                    "Unreferenced" to bytes(o.unreferenced),
                ),
            ),
            ReportSection(
                "Renditions of imported originals",
                listOf("Originals = done + failed + not possible + pending + not needed."),
                t.renditions.flatMap { (kind, r) ->
                    listOf(
                        "$kind originals" to "${r.originals}",
                        "$kind done" to "${r.done}",
                        "$kind failed" to "${r.failed}",
                        "$kind not possible" to "${r.notPossible}",
                        "$kind pending" to "${r.pending}",
                        "$kind not needed" to "${r.notNeeded}",
                    )
                },
            ),
            ReportSection(
                "Fixity",
                listOf("Every imported original is read back and its SHA-256 compared with the one taken at intake."),
                listOf(
                    "Imported originals" to "${t.fixity.importedOriginals}",
                    "Verified" to "${t.fixity.verified}",
                    "Mismatches" to "${t.fixity.mismatches}",
                    "Other verification problems" to "${t.fixity.verifyProblems}",
                    "Hash refused at promotion" to "${t.fixity.promotionMismatches}",
                ),
            ),
        )
    }

    private fun listed(facts: ReportFacts): List<ReportSection> =
        listOf(
            ReportSection(
                "Stored, not bound (${facts.storedNotBound.size})",
                listOf("Originals promoted for rows that then failed; kept as records under an unused memo id. Expected: none."),
                capped(facts.storedNotBound.map { "Row ${it.rowNumber}: ${it.path}" to "file ${it.fileId}, version ${it.versionId}" }),
            ),
            ReportSection(
                "Renditions failed (${facts.failedRenditions.size})",
                listOf("Imported originals without a preview or text, such as an encrypted presentation; the original is kept."),
                capped(facts.failedRenditions.map { "Row ${it.rowNumber}: ${it.path}" to it.kinds.joinToString(", ") }),
            ),
        )

    private fun capped(lines: List<Pair<String, String>>): List<Pair<String, String>> =
        if (lines.size <= MAX_LISTED) lines else lines.take(MAX_LISTED) + ("…" to "${lines.size - MAX_LISTED} more in the workbook")

    private fun bytes(count: FilesAndBytes) = "${count.files} (${count.bytes} bytes)"

    private fun yes(value: Boolean) = if (value) "yes" else "no"
}
