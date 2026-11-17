package com.memo.imports.internal.report

import com.memo.common.poi.PoiLimits
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.RenditionKind
import com.memo.files.RenditionState
import com.memo.imports.internal.ImportBatch
import com.memo.imports.internal.ImportMappingStore
import org.apache.poi.poifs.crypt.temp.SXSSFWorkbookWithCustomZipEntrySource
import org.apache.poi.ss.usermodel.Sheet
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * The report's workbook (design E06 §8, part 2): sheets Summary, Rows, Files, Originals, Problems and Mappings, every
 * value a string cell through [ReportCells]. Streamed (POI's SXSSF, its temporary sheet data encrypted with a key held in
 * memory), the reads a page at a time, so a batch of any size fits. With the tenant bound, outside a transaction.
 */
@Component
class ReportWorkbook(
    private val reads: ReportSheetReads,
    private val rowSheets: ReportRowSheets,
    private val mappings: ImportMappingStore,
    private val transactions: TenantTransactions,
) {
    fun write(
        batch: ImportBatch,
        facts: ReportFacts,
        renditions: Map<UUID, Map<RenditionKind, RenditionState>>,
        out: Path,
    ) {
        PoiLimits.apply()
        val workbook = SXSSFWorkbookWithCustomZipEntrySource()
        try {
            summary(workbook.createSheet("Summary"), facts)
            rowSheets.rows(batch, workbook.createSheet("Rows"))
            rowSheets.files(batch, workbook.createSheet("Files"), renditions)
            originals(batch, workbook.createSheet("Originals"))
            problems(batch, workbook.createSheet("Problems"))
            mappings(batch, workbook.createSheet("Mappings"))
            Files.newOutputStream(out).use(workbook::write)
        } finally {
            workbook.close()
        }
    }

    private fun summary(
        sheet: Sheet,
        facts: ReportFacts,
    ) {
        val lines =
            facts.header +
                ("Result" to if (facts.balance.balanced) "BALANCED" else "UNBALANCED") +
                ("Failing lines" to facts.balance.failing.joinToString(", ")) +
                ("Complete" to facts.balance.complete.toString()) +
                ("Valid" to facts.balance.valid.toString()) +
                ReportSummary
                    .document("", facts)
                    .sections
                    .drop(2)
                    .flatMap { section -> section.lines.map { (label, value) -> "${section.heading}: $label" to value } }
        ReportCells.row(sheet, 0, listOf("Item", "Value"))
        lines.forEachIndexed { i, (label, value) -> ReportCells.row(sheet, i + 1, listOf(label, value)) }
    }

    private fun originals(
        batch: ImportBatch,
        sheet: Sheet,
    ) {
        ReportCells.row(sheet, 0, listOf("Path", "State", "Size", "SHA-256", "Media type", "Refusal", "Signature", "Received at"))
        var index = 1
        var after: String? = null
        do {
            val page = read { reads.originals(batch.id, after, ImportEndLimits.PAGE) }
            page.forEach { o ->
                ReportCells.row(
                    sheet,
                    index++,
                    listOf(o.path, o.state, o.size, o.sha256, o.mediaType, o.refusal, o.signature, o.receivedAt),
                )
            }
            after = page.lastOrNull()?.path
        } while (page.size == ImportEndLimits.PAGE)
    }

    private fun problems(
        batch: ImportBatch,
        sheet: Sheet,
    ) {
        ReportCells.row(sheet, 0, listOf("Stage", "Level", "Code", "Sheet", "Row", "Column", "Details"))
        var index = 1
        var after: UUID? = null
        do {
            val page = read { reads.problems(batch.id, after, ImportEndLimits.PAGE) }
            page.forEach { p ->
                ReportCells.row(sheet, index++, listOf(p.stage, p.level, p.code, p.sheet, p.rowNumber, p.column, p.params))
            }
            after = page.lastOrNull()?.id
        } while (page.size == ImportEndLimits.PAGE)
    }

    private fun mappings(
        batch: ImportBatch,
        sheet: Sheet,
    ) {
        ReportCells.row(
            sheet,
            0,
            listOf("Kind", "Legacy value", "Legacy order", "Target", "Proposed", "Confirmed by", "Confirmed at", "Rows"),
        )
        read { mappings.all(batch.id) }.forEachIndexed { i, m ->
            ReportCells.row(
                sheet,
                i + 1,
                listOf(m.kind, m.legacyValue, m.legacyOrder, m.target, m.proposed, m.confirmedBy, m.confirmedAt, m.rowCount),
            )
        }
    }

    private fun <T> read(block: () -> T): T = transactions.readInTenant(TenantContext.current(), block)
}
