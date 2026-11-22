package com.memo.search.internal.decision

import com.memo.common.poi.PoiLimits
import com.memo.common.poi.SheetCells
import com.memo.memo.ReportColumn
import com.memo.memo.ReportDocument
import com.memo.memo.ReportPages
import com.memo.memo.ReportSection
import com.memo.memo.ReportTable
import com.memo.search.RegisterLevel
import org.apache.poi.poifs.crypt.temp.SXSSFWorkbookWithCustomZipEntrySource
import org.apache.poi.ss.usermodel.Sheet
import org.springframework.stereotype.Component
import java.io.OutputStream
import java.time.ZoneOffset

/**
 * What both files say of an export, so the spreadsheet and the PDF agree (design E08 §1.5): a row's cells, the about lines
 * and the number check's lines. A restricted line carries its line's fields only: committee, meeting, date, item, the
 * resolution number and its state, and "Restricted item" with the reason for this viewer.
 */
internal object ExportContent {
    val DECISION_HEADERS =
        listOf(
            "Committee",
            "Meeting",
            "Meeting date",
            "Item",
            "Title",
            "Outcome",
            "Resolution number",
            "Number state",
            "Decision text",
            "Conditions",
            "Valid until",
            "State",
            "Memo number",
            "Memo type",
            "Decided at (UTC)",
            "Minutes approved at (UTC)",
            "Exceptions",
            "Link",
        )

    val CHECK_HEADERS =
        listOf(
            "Committee",
            "Period",
            "First",
            "Last",
            "In force",
            "Void",
            "Missing",
            "Duplicates",
            "Outside the range",
            "All accounted for",
        )

    val VOID_HEADERS = listOf("Committee", "Period", "Number", "Voided on", "Reason")

    /** One row of the Decisions sheet. */
    fun decision(
        export: RegisterExport,
        exported: ExportRow,
    ): List<Any?> {
        val row = exported.row
        val full = row.level == RegisterLevel.FULL
        return listOf(
            exported.committee,
            row.meetingLabel,
            row.meetingDate,
            row.itemNumber,
            if (full) row.title else RegisterWords.line(row.reason),
            row.outcomeLabel ?: row.outcomeKind?.let(RegisterWords::words),
            row.resolutionNumber,
            RegisterWords.numberState(row.resolutionState),
            row.text,
            RegisterWords.conditions(row.conditions),
            row.validUntil,
            RegisterWords.words(row.state),
            row.memoNumber,
            exported.memoType,
            row.decidedAt.takeIf { full },
            row.finalAt,
            RegisterWords.flags(row, export.today).joinToString("; ").ifEmpty { null },
            "${export.appUrl}/meetings/${row.meetingId}",
        )
    }

    /** The about block: who, when, the filters, the counts and what a restricted line is. */
    fun about(export: RegisterExport): List<Pair<String, String>> {
        val lines = export.rows.count { it.row.level == RegisterLevel.LINE }
        return listOf(
            "Organisation" to export.organisation,
            "Produced by" to export.producedBy,
            "Produced at (UTC)" to export.producedAt.atOffset(ZoneOffset.UTC).toString(),
            "Produced at (${export.zone.id})" to RegisterWords.at(export.producedAt, export.zone),
        ) + export.filters +
            listOf(
                "Decisions" to export.rows.size.toString(),
                "Restricted items" to "$lines. ${RegisterWords.RULE}",
                "What a restricted item is" to RegisterWords.RULE_DETAIL,
                "The number check" to RegisterWords.CHECK_DETAIL,
            )
    }

    /** One line of the number check. */
    fun check(c: NumberCheck): List<Any?> {
        val check = c.check ?: return listOf(c.committee, RegisterWords.period(c.period), "No counter for this period")
        val missing =
            if (check.missingCount == 0L) {
                "None"
            } else {
                check.missing.joinToString(", ") +
                    (check.missingCount - check.missing.size).takeIf { it > 0 }?.let { " and $it more" }.orEmpty()
            }
        return listOf(
            c.committee,
            RegisterWords.period(c.period),
            check.firstNumber,
            check.lastNumber,
            check.inForce,
            check.void,
            missing,
            check.duplicates.joinToString(", ").ifEmpty { "None" },
            check.outOfRange.joinToString(", ").ifEmpty { "None" },
            if (check.complete) "Yes" else "No",
        )
    }

    /** One void number of a check: its date in the tenant's zone and its reason, or why the reason is withheld. */
    fun void(
        export: RegisterExport,
        c: NumberCheck,
        void: VoidNumber,
    ): List<Any?> =
        listOf(
            c.committee,
            RegisterWords.period(c.period),
            void.number,
            void.voidedAt?.let { RegisterWords.day(it.atZone(export.zone).toLocalDate()) },
            void.reason ?: "Reason not shown: ${void.reasonWithheld?.lowercase()}",
        )
}

/**
 * The register's spreadsheet (design E08 §1.5, flow AT): sheet "Decisions" in the order asked, "About this export" and
 * "Number check". Every value is a string cell with the formula-injection prefix ([SheetCells]); streamed through POI's
 * SXSSF with its temporary data encrypted under a key held in memory, as the other exports.
 */
@Component
class RegisterWorkbook {
    fun write(
        export: RegisterExport,
        out: OutputStream,
    ) {
        PoiLimits.apply()
        val workbook = SXSSFWorkbookWithCustomZipEntrySource()
        try {
            val decisions = workbook.createSheet(DECISIONS)
            SheetCells.row(decisions, 0, ExportContent.DECISION_HEADERS)
            export.rows.forEachIndexed { index, row -> SheetCells.row(decisions, index + 1, ExportContent.decision(export, row)) }
            widths(decisions, DECISION_WIDTHS)
            val about = workbook.createSheet(ABOUT)
            SheetCells.row(about, 0, listOf(RegisterWords.TITLE))
            ExportContent.about(export).forEachIndexed { index, (label, value) -> SheetCells.row(about, index + 2, listOf(label, value)) }
            widths(about, listOf(LABEL_WIDTH, VALUE_WIDTH))
            numberCheck(workbook.createSheet(CHECK), export)
            workbook.write(out)
        } finally {
            workbook.close()
        }
    }

    /** The checks, then the void numbers under their own header. */
    private fun numberCheck(
        sheet: Sheet,
        export: RegisterExport,
    ) {
        SheetCells.row(sheet, 0, ExportContent.CHECK_HEADERS)
        if (export.checks.isEmpty()) {
            SheetCells.row(sheet, 1, listOf("No resolution numbers in this export."))
            return
        }
        export.checks.forEachIndexed { index, check -> SheetCells.row(sheet, index + 1, ExportContent.check(check)) }
        var next = export.checks.size + 2
        SheetCells.row(sheet, next++, listOf("Void numbers"))
        SheetCells.row(sheet, next++, ExportContent.VOID_HEADERS)
        val voids = export.checks.flatMap { check -> check.voids.map { check to it } }
        if (voids.isEmpty()) SheetCells.row(sheet, next, listOf("None"))
        voids.forEachIndexed { index, (check, void) -> SheetCells.row(sheet, next + index, ExportContent.void(export, check, void)) }
        widths(sheet, CHECK_WIDTHS)
    }

    private fun widths(
        sheet: Sheet,
        characters: List<Int>,
    ) = characters.forEachIndexed { column, chars -> sheet.setColumnWidth(column, chars * CHAR_UNITS) }

    companion object {
        const val MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val DECISIONS = "Decisions"
        const val ABOUT = "About this export"
        const val CHECK = "Number check"
        private const val CHAR_UNITS = 256
        private const val LABEL_WIDTH = 28
        private const val VALUE_WIDTH = 100
        private val DECISION_WIDTHS = listOf(28, 14, 12, 6, 40, 22, 16, 10, 60, 40, 12, 10, 16, 18, 22, 22, 24, 50)
        private val CHECK_WIDTHS = listOf(28, 10, 8, 8, 9, 6, 20, 12, 16, 16)
    }
}

/**
 * The register's PDF (design E08 §1.5, flow AT): "Register of committee decisions", landscape, through [ReportPages]
 * (ADR-0052): the about block first, a table per committee in date order, the number check last, "Page n of N" and
 * "Produced by {name} on {date} — {filters}" on every page. Built on demand and streamed, never stored; not a record.
 */
@Component
class RegisterPdf(
    private val pages: ReportPages,
) {
    fun write(
        export: RegisterExport,
        out: OutputStream,
    ) {
        val committees =
            export.rows
                .groupBy { it.row.forumId }
                .values
                .sortedBy { it.first().committee }
                .map { rows ->
                    val dated = rows.sortedWith(compareBy({ it.row.meetingDate }, { it.row.decidedAt }, { it.row.decisionId }))
                    ReportSection(
                        rows.first().committee,
                        paragraphs = listOf(count(rows.size)),
                        table = ReportTable(COLUMNS, dated.map { cells(export, it) }),
                    )
                }
        val sections =
            listOf(ReportSection("About this export", lines = ExportContent.about(export))) +
                committees.ifEmpty { listOf(ReportSection("Decisions", listOf("No decisions match these filters."))) } +
                numberCheck(export)
        val filters = export.filters.joinToString("; ") { (label, value) -> "$label: $value" }
        val produced = RegisterWords.at(export.producedAt, export.zone)
        pages.render(
            ReportDocument(
                title = RegisterWords.TITLE,
                header = listOf(RegisterWords.TITLE, export.organisation).filter { it.isNotEmpty() }.joinToString(" · "),
                footer = "Produced by ${export.producedBy} on $produced — $filters",
                sections = sections,
                landscape = true,
                pagesInWords = true,
            ),
            out,
        )
    }

    private fun count(rows: Int) = if (rows == 1) "1 decision" else "$rows decisions"

    /** A row's cells: the same content as the spreadsheet's, in fewer, wider columns. */
    private fun cells(
        export: RegisterExport,
        exported: ExportRow,
    ): List<String> {
        val row = exported.row
        val full = row.level == RegisterLevel.FULL
        val decision =
            listOfNotNull(
                row.text,
                RegisterWords.conditions(row.conditions)?.let { "Conditions:\n$it" },
                row.validUntil?.let { "Valid until ${RegisterWords.day(it)}" },
                RegisterWords.flags(row, export.today).joinToString("; ").ifEmpty { null },
            )
        return listOf(
            listOfNotNull(row.meetingLabel, RegisterWords.day(row.meetingDate)).joinToString("\n"),
            row.itemNumber.orEmpty(),
            if (full) row.title.orEmpty() else RegisterWords.line(row.reason),
            row.outcomeLabel ?: row.outcomeKind?.let(RegisterWords::words).orEmpty(),
            listOfNotNull(row.resolutionNumber, RegisterWords.numberState(row.resolutionState)).joinToString("\n"),
            decision.joinToString("\n"),
            listOfNotNull(
                RegisterWords.words(row.state),
                row.decidedAt.takeIf { full }?.let { "Decided ${RegisterWords.at(it, export.zone)}" },
                row.finalAt?.let { "Minutes approved ${RegisterWords.day(it.atZone(export.zone).toLocalDate())}" },
            ).joinToString("\n"),
            listOfNotNull(row.memoNumber, exported.memoType).joinToString("\n"),
        )
    }

    private fun numberCheck(export: RegisterExport): List<ReportSection> {
        val check =
            ReportSection(
                "Number check",
                listOf(RegisterWords.CHECK_DETAIL) +
                    listOfNotNull("No resolution numbers in this export.".takeIf { export.checks.isEmpty() }),
                table =
                    export.checks.takeIf { it.isNotEmpty() }?.let { checks ->
                        ReportTable(ExportContent.CHECK_HEADERS.map(::ReportColumn), checks.map { c -> ExportContent.check(c).map(::text) })
                    },
            )
        val voids = export.checks.flatMap { c -> c.voids.map { ExportContent.void(export, c, it).map(::text) } }
        if (voids.isEmpty()) return listOf(check)
        return listOf(check, ReportSection("Void numbers", table = ReportTable(VOID_COLUMNS, voids)))
    }

    private fun text(value: Any?): String = value?.toString().orEmpty()

    private companion object {
        val COLUMNS =
            listOf(
                ReportColumn("Meeting", 1.1f),
                ReportColumn("Item", 0.45f),
                ReportColumn("Title", 2f),
                ReportColumn("Outcome", 1.1f),
                ReportColumn("Resolution", 1.1f),
                ReportColumn("Decision", 3.6f),
                ReportColumn("State", 1.2f),
                ReportColumn("Memo", 1f),
            )
        val VOID_COLUMNS =
            listOf(
                ReportColumn("Committee", 2f),
                ReportColumn("Period"),
                ReportColumn("Number"),
                ReportColumn("Voided on"),
                ReportColumn("Reason", 4f),
            )
    }
}
