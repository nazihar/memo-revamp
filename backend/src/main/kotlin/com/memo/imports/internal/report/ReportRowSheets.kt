package com.memo.imports.internal.report

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.Files
import com.memo.files.RenditionKind
import com.memo.files.RenditionState
import com.memo.imports.internal.ImportBatch
import com.memo.imports.internal.ImportMappingStore
import com.memo.imports.internal.MappingKind
import com.memo.imports.internal.dryrun.LegacyDateText
import com.memo.imports.internal.dryrun.MemoColumns
import com.memo.imports.internal.dryrun.PersonKeys
import com.memo.imports.internal.dryrun.RowResult
import org.apache.poi.ss.usermodel.Sheet
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The report's Rows and Files sheets (design E06 §8): each Memos row with its legacy number as given and as stored, its
 * result, codes, memo id, values and dates as given and as stored, and its linked people (user ids only); each Files row
 * with the original it matched, its SHA-256 at intake, as expected and as stored, the verification result, the stored
 * file and version, and the renditions' states or the refusal. A page at a time; with the tenant bound.
 */
@Component
class ReportRowSheets(
    private val reads: ReportSheetReads,
    private val mappings: ImportMappingStore,
    private val files: Files,
    private val transactions: TenantTransactions,
) {
    fun rows(
        batch: ImportBatch,
        sheet: Sheet,
    ) {
        ReportCells.row(sheet, 0, ROW_HEADER)
        val people =
            read { mappings.all(batch.id, MappingKind.PERSON) }
                .filter { it.confirmed && it.target != null }
                .associate { it.legacyValue to it.target!! }
        var index = 1
        var after = 0
        do {
            val page = read { reads.rows(batch.id, after, ImportEndLimits.PAGE) }
            val ids = page.map { it.id }
            val (codes, approvers) = read { reads.codes(ids) to reads.approvers(ids) }
            page.forEach { row ->
                val linked =
                    (
                        listOfNotNull(PersonKeys.of(row.cells[MemoColumns.INITIATOR_EMAIL], row.cells[MemoColumns.INITIATOR_ID])) +
                            approvers[row.id].orEmpty()
                    ).mapNotNull(people::get)
                        .distinct()
                ReportCells.row(sheet, index++, rowValues(batch, row, codes[row.id].orEmpty(), linked))
            }
            after = page.lastOrNull()?.rowNumber ?: after
        } while (page.size == ImportEndLimits.PAGE)
    }

    fun files(
        batch: ImportBatch,
        sheet: Sheet,
        renditions: Map<UUID, Map<RenditionKind, RenditionState>>,
    ) {
        ReportCells.row(sheet, 0, FILE_HEADER)
        val verified = read { reads.verified(batch.id) }
        var index = 1
        var after: Pair<Int, UUID>? = null
        do {
            val page = read { reads.files(batch.id, after, ImportEndLimits.PAGE) }
            val stored = read { files.currentVersions(page.mapNotNull { it.storedFileId }) }
            page.forEach { f ->
                val version = f.storedFileId?.let(stored::get)
                val states = f.storedVersionId?.let(renditions::get).orEmpty()
                val verification =
                    when {
                        f.storedVersionId == null || f.rowResult != RowResult.IMPORTED.name -> null
                        else -> verified[f.rowNumber]?.joinToString(", ") ?: "OK"
                    }
                ReportCells.row(
                    sheet,
                    index++,
                    listOf(
                        f.rowNumber,
                        f.path,
                        f.role,
                        f.match,
                        f.rowResult,
                        f.intakePath,
                        f.intakeSize,
                        f.intakeSha256,
                        f.expectedSha256,
                        version?.sha256,
                        verification,
                        f.storedFileId,
                        f.storedVersionId,
                        states[RenditionKind.PDF],
                        states[RenditionKind.TEXT],
                        f.refusal,
                    ),
                )
            }
            after = page.lastOrNull()?.let { it.rowNumber to it.id } ?: after
        } while (page.size == ImportEndLimits.PAGE)
    }

    private fun rowValues(
        batch: ImportBatch,
        row: ReportRow,
        codes: List<String>,
        linked: List<String>,
    ): List<Any?> {
        val imported = row.result == RowResult.IMPORTED.name
        val number = row.cells[MemoColumns.LEGACY_NUMBER]

        fun date(column: String): List<String?> {
            val given = row.cells[column]
            return listOf(given, given?.takeIf { imported }?.let(LegacyDateText::parse)?.toString())
        }
        return listOf(
            row.rowNumber,
            number,
            number?.takeIf { imported }?.let { (batch.settings.numberPrefix ?: "") + it.trim() },
            row.result,
            codes.joinToString(", "),
            row.errors,
            row.warnings,
            row.memoId?.takeIf { imported },
            row.cells[MemoColumns.SUBJECT],
            row.cells[MemoColumns.STATUS],
            row.cells[MemoColumns.MEMO_TYPE],
            row.cells[MemoColumns.CLASSIFICATION],
            row.cells[MemoColumns.ORIGIN_UNIT],
        ) + date(MemoColumns.RAISED_ON) + date(MemoColumns.DECIDED_ON) + date(MemoColumns.CLOSED_ON) +
            listOf(row.cells[MemoColumns.INITIATOR_NAME], linked.joinToString(", "))
    }

    private fun <T> read(block: () -> T): T = transactions.readInTenant(TenantContext.current(), block)

    private companion object {
        val ROW_HEADER =
            listOf(
                "Row",
                "Legacy number",
                "Reference stored",
                "Result",
                "Codes",
                "Errors",
                "Warnings",
                "Memo id",
                "Subject",
                "Status",
                "Type",
                "Classification",
                "Unit",
                "Raised on",
                "Raised on stored",
                "Decided on",
                "Decided on stored",
                "Closed on",
                "Closed on stored",
                "Initiator",
                "Linked people (user ids)",
            )
        val FILE_HEADER =
            listOf(
                "Row",
                "Path",
                "Role",
                "Match",
                "Memos row result",
                "Original received",
                "Size",
                "SHA-256 at intake",
                "SHA-256 expected",
                "SHA-256 stored",
                "Verification",
                "File id",
                "Version id",
                "PDF",
                "TEXT",
                "Refusal",
            )
    }
}
