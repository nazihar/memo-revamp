package com.memo.analytics.export

import com.memo.analytics.measures.CommitteeAttendance
import com.memo.analytics.measures.MemberAttendance
import com.memo.analytics.measures.Period
import com.memo.common.poi.PoiLimits
import com.memo.common.poi.SheetCells
import org.apache.poi.poifs.crypt.temp.SXSSFWorkbookWithCustomZipEntrySource
import org.springframework.stereotype.Component
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** What the attendance export holds (design E08 §2.4): the header block's facts and one row per seat holder. */
data class AttendanceExport(
    val committee: String,
    val period: Period,
    val heldMeetings: Int,
    val committeeTotals: CommitteeAttendance,
    /** By name. */
    val rows: List<MemberAttendance>,
    val names: Map<UUID, String>,
    val producedBy: String,
    val producedAt: Instant,
    val zone: ZoneId,
)

/**
 * The attendance export for the annual report (design E08 §2.4; UK Code Provision 14): a header block (committee, period,
 * held meetings, overall attendance, who produced it and when), then one row per seat holder with the same definitions
 * as the screen (M1). Every value is a string cell with the formula-injection prefix ([SheetCells]), streamed through
 * SXSSF with its temporary data encrypted, as the other exports. No item content of any kind is in it.
 */
@Component
class AttendanceWorkbook {
    fun write(
        export: AttendanceExport,
        out: OutputStream,
    ) {
        PoiLimits.apply()
        val workbook = SXSSFWorkbookWithCustomZipEntrySource()
        try {
            val sheet = workbook.createSheet("Attendance")
            val produced = export.producedAt.atZone(export.zone).format(STAMP)
            val header =
                listOf(
                    listOf("Committee", export.committee),
                    listOf("Period", "${export.period.from} to ${export.period.to}"),
                    listOf("Meetings held", export.heldMeetings),
                    listOf(
                        "Overall attendance",
                        "${export.committeeTotals.attended} of ${export.committeeTotals.expected}" +
                            (export.committeeTotals.ratePercent?.let { " ($it %)" } ?: ""),
                    ),
                    listOf("Produced by", export.producedBy),
                    listOf("Produced on", "$produced (${export.producedAt} UTC)"),
                    listOf("Note", NOTE),
                )
            header.forEachIndexed { index, values -> SheetCells.row(sheet, index, values) }
            val first = header.size + 1
            SheetCells.row(sheet, first, HEADERS)
            export.rows.forEachIndexed { index, row -> SheetCells.row(sheet, first + 1 + index, values(row, export.names)) }
            workbook.write(out)
        } finally {
            workbook.close()
        }
    }

    private fun values(
        row: MemberAttendance,
        names: Map<UUID, String>,
    ): List<Any?> =
        listOf(
            names[row.userId] ?: row.userId,
            ROLES.getValue(row.role.name),
            "${row.seatFrom} to ${row.seatTo}",
            row.expected,
            row.attended,
            row.inPerson,
            row.remote,
            row.apologies,
            row.absent,
            row.byAlternate,
            row.ratePercent?.let { "$it %" } ?: "",
        )

    companion object {
        const val MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val NOTE =
            "Attended: present in person or remotely at any time. Represented by alternate: an alternate attended in the " +
                "member's place; not counted as the member's attendance."
        val HEADERS =
            listOf(
                "Member",
                "Role",
                "Seat in the period",
                "Meetings expected",
                "Attended",
                "In person",
                "Remote",
                "Apologies",
                "Absent",
                "Represented by alternate",
                "Rate",
            )
        val ROLES = mapOf("CHAIR" to "Chair", "VICE_CHAIR" to "Vice-chair", "MEMBER" to "Member")
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH)
    }
}
