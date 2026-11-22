package com.memo.actions.internal

import com.memo.common.poi.PoiLimits
import com.memo.common.poi.SheetCells
import org.apache.poi.poifs.crypt.temp.SXSSFWorkbookWithCustomZipEntrySource
import org.springframework.stereotype.Component
import java.io.OutputStream

/**
 * The tracker's export (design E07 §3, flow AN): one sheet of the rows under the filters, every value a string cell with
 * the formula-injection prefix ([SheetCells]: a title beginning with `=` is written `'=…`), streamed (POI's SXSSF, its
 * temporary data encrypted with a key held in memory, as the register of declarations). The colour is always written
 * with its word.
 */
@Component
class ActionTrackerWorkbook {
    fun write(
        export: TrackerExport,
        out: OutputStream,
    ) {
        PoiLimits.apply()
        val workbook = SXSSFWorkbookWithCustomZipEntrySource()
        try {
            val sheet = workbook.createSheet("Actions")
            SheetCells.row(sheet, 0, HEADERS)
            export.rows.forEachIndexed { index, row -> SheetCells.row(sheet, index + 1, values(row)) }
            if (export.truncated) {
                SheetCells.row(sheet, export.rows.size + 1, listOf("Only the first ${export.rows.size} actions are exported."))
            }
            workbook.write(out)
        } finally {
            workbook.close()
        }
    }

    private fun values(row: ActionSummary): List<Any?> =
        listOf(
            row.committee,
            row.reference ?: DRAFT,
            row.title,
            row.sourceLabel,
            row.owner?.let { it.position?.title ?: it.person?.name },
            row.owner?.holder?.name,
            row.dueDate,
            row.originalDueDate,
            row.dueRevisions,
            row.status,
            row.rag.colour,
            row.rag.word,
            row.confidence,
            row.lastUpdateAt,
            row.stepName,
            row.priority,
        )

    companion object {
        const val MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val DRAFT = "Draft action"
        val HEADERS =
            listOf(
                "Committee",
                "Reference",
                "Action",
                "Source",
                "Owner",
                "Held by",
                "Due",
                "Originally due",
                "Times re-dated",
                "Status",
                "Colour",
                "In words",
                "Owner's confidence",
                "Last update (UTC)",
                "Step",
                "Priority",
            )
    }
}
