package com.memo.demo

import com.memo.imports.LegacySample
import com.memo.imports.LegacySampleMappings
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The synthetic legacy sample (Phase 3 E06 T10, design E06 §1): what a bank's old document system might export, made
 * deterministically at run time (the same bytes every time, so its hashes and the dry run's input hash repeat; nothing
 * binary is committed). A workbook in the 1904 date system with Memos, Files and Approvals sheets, and a folder of
 * originals in the formats a legacy archive holds: .docx, .pdf, a Word 97 .doc, an Excel 97 .xls, a three-page TIFF scan,
 * a .zip of annexes, two paths that differ only in case, and a `Thumbs.db` the browser would skip.
 *
 * The rows are a little messy on purpose: date cells with and without a time beside ISO text, an impossible date, a
 * legacy number given twice, a Files path in another case than the folder's, an initiator given by name only (who left
 * before the move), one with an email nobody in the tenant has, the deactivated demo leaver linked by email, an attachment
 * classified above its memo, and a status still in flight. Four memos import; four rows are rejected with their reasons.
 *
 * The `.doc` is a minimal Word 97 compound file (a Word document stream only): the Tika server types it as Word, but no
 * converter can open it, so its preview fails and the report lists it under failed renditions, as it would an encrypted
 * PowerPoint (ADR-0055 amendment).
 */
object SyntheticLegacy {
    const val BATCH = "Legacy DMS sample, 2019–2021"
    const val SOURCE = "LEGACY-DMS"
    const val ZONE = "Asia/Dhaka"
    const val WORKBOOK = "legacy-dms-export.xlsx"

    /** The initiator given by name only, who left before the move: search finds their memo by this name. */
    const val DEPARTED = "Rafiq Hossain"

    /** The legacy numbers that import, and those the dry run rejects. */
    val IMPORTED = listOf("HO/2019/0012", "HO/2019/0047", "HO/2020/0003", "HO/2020/0118")
    val REJECTED = listOf("HO/2021/0007", "HO/2021/0009", "HO/2021/0015")

    /** Files a browser leaves out of a chosen folder (design E06 §5.1); the seeder leaves them out too. */
    val SYSTEM_FILES = setOf("Thumbs.db", ".DS_Store", "desktop.ini")

    val MAPPINGS =
        LegacySampleMappings(
            statuses = mapOf("Approved" to "APPROVED", "Rejected" to "REJECTED", "Closed" to "CLOSED", "Pending with GM" to "NOT_IMPORTED"),
            types = mapOf("Purchase" to "PRC", "Expense" to "EXP", "Policy" to "POL"),
            units =
                mapOf(
                    "BR-KHULNA" to "BR-KHU",
                    "BR-GULSHAN" to "BR-GUL",
                    "BR-UTTARA" to "BR-UTT",
                    "HO-FIN" to "FIN",
                    "HO-RISK" to "RSK",
                ),
            classifications = listOf("Internal" to "Internal", "Confidential" to "Confidential"),
        )

    /** The sample as the seeder imports it: the folder without its system files. */
    fun sample(): LegacySample =
        LegacySample(
            BATCH,
            SOURCE,
            ZONE,
            WORKBOOK,
            workbook(),
            folder().filterKeys { it.substringAfterLast('/') !in SYSTEM_FILES },
            MAPPINGS,
        )

    /** The folder as exported: relative paths to content, in listing order. */
    fun folder(): Map<String, ByteArray> =
        linkedMapOf(
            "2019/HO-2019-0012/memo.docx" to LegacyFiles.docx("Generator purchase for the Khulna branch", GENERATOR),
            "2019/HO-2019-0012/quotation.pdf" to LegacyFiles.pdf("Quotation: standby generators", QUOTATION),
            "2019/HO-2019-0047/memo.doc" to LegacyFiles.doc("Renewal of the Gulshan branch lease"),
            "2019/HO-2019-0047/rent-schedule.xls" to LegacyFiles.xls(),
            "2020/HO-2020-0003/scan.tif" to LegacyFiles.tiff(SCAN_PAGES),
            "2020/HO-2020-0003/annexes.zip" to annexes(),
            "2020/HO-2020-0118/memo.pdf" to LegacyFiles.pdf("Revision of the cash-in-transit insurance cover", INSURANCE),
            "2021/HO-2021-0007/Memo.pdf" to LegacyFiles.pdf("Office stationery (draft)", listOf("Superseded draft.")),
            "2021/HO-2021-0007/memo.pdf" to LegacyFiles.pdf("Office stationery for the second half", listOf("Paper, toner and files.")),
            "2021/HO-2021-0009/memo.pdf" to LegacyFiles.pdf("Repairs to the Uttara branch strongroom", listOf("Door hinge and time lock.")),
            "2021/HO-2021-0015/memo.docx" to LegacyFiles.docx("Upgrade of the core banking licence", listOf("Awaiting the GM's decision.")),
            "Thumbs.db" to LegacyFiles.compound("Catalog" to ByteArray(THUMBS_BYTES)),
        )

    /** The export's workbook, in the 1904 date system (old Mac Excel); dates as date cells or as text. */
    fun workbook(): ByteArray =
        XSSFWorkbook().use { book ->
            book.ctWorkbook.addNewWorkbookPr().date1904 = true
            book.properties.coreProperties.setCreated(CREATED)
            val dates = book.createCellStyle().apply { dataFormat = book.createDataFormat().getFormat("dd/mm/yyyy") }
            val times = book.createCellStyle().apply { dataFormat = book.createDataFormat().getFormat("dd/mm/yyyy hh:mm") }

            fun write(
                name: String,
                rows: List<List<Any>>,
            ) {
                val sheet = book.createSheet(name)
                rows.forEachIndexed { r, values ->
                    val row = sheet.createRow(r)
                    values.forEachIndexed { c, value -> cell(row.createCell(c), value, dates, times) }
                }
            }
            write("Memos", listOf(MEMO_HEADER) + MEMOS)
            write("Files", listOf(FILE_HEADER) + FILES)
            write("Approvals", listOf(APPROVAL_HEADER) + APPROVALS)
            LegacyFiles.fixedTimes(ByteArrayOutputStream().also(book::write).toByteArray())
        }

    private fun cell(
        cell: Cell,
        value: Any,
        dates: CellStyle,
        times: CellStyle,
    ) {
        when (value) {
            is LocalDate -> {
                cell.setCellValue(DateUtil.getExcelDate(value.atStartOfDay(), true))
                cell.cellStyle = dates
            }

            is LocalDateTime -> {
                cell.setCellValue(DateUtil.getExcelDate(value, true))
                cell.cellStyle = times
            }

            else -> {
                cell.setCellValue(value.toString())
            }
        }
    }

    private val MEMO_HEADER =
        listOf(
            "legacy_number",
            "memo_type",
            "subject",
            "status",
            "classification",
            "origin_unit",
            "raised_on",
            "decided_on",
            "closed_on",
            "initiator_name",
            "initiator_email",
        )
    private val FILE_HEADER = listOf("legacy_number", "path", "role", "title", "classification")
    private val APPROVAL_HEADER = listOf("legacy_number", "order", "name", "email", "position", "decision", "decided_on", "remark")

    private val MEMOS: List<List<Any>> =
        listOf(
            listOf(
                "HO/2019/0012",
                "Purchase",
                "Generator purchase for the Khulna branch",
                "Approved",
                "Internal",
                "BR-KHULNA",
                LocalDate.parse("2019-02-10"),
                LocalDate.parse("2019-02-21"),
                "",
                "Demo User",
                "user@demo.example",
            ),
            listOf(
                "HO/2019/0047",
                "Expense",
                "Renewal of the Gulshan branch lease",
                "Closed",
                "Internal",
                "BR-GULSHAN",
                LocalDate.parse("2019-07-01"),
                LocalDate.parse("2019-07-15"),
                LocalDate.parse("2019-09-30"),
                "Demo Leaver",
                "leaver@demo.example",
            ),
            listOf(
                "HO/2020/0003",
                "Expense",
                "Sponsorship of the regional cricket league",
                "Rejected",
                "Internal",
                "HO-FIN",
                "2020-01-12",
                "2020-01-20",
                "",
                DEPARTED,
                "",
            ),
            listOf(
                "HO/2020/0118",
                "Policy",
                "Revision of the cash-in-transit insurance cover",
                "Approved",
                "Confidential",
                "HO-RISK",
                LocalDateTime.parse("2020-06-03T10:30"),
                "2020-06-18",
                "",
                "Nasreen Akter",
                "n.akter@legacy-bank.example",
            ),
            // Given twice in the export: both rows are refused.
            stationery(),
            stationery(),
            listOf(
                "HO/2021/0009",
                "Expense",
                "Repairs to the Uttara branch strongroom",
                "Approved",
                "Internal",
                "BR-UTTARA",
                "31/02/2021",
                "2021-03-04",
                "",
                "Demo User",
                "user@demo.example",
            ),
            listOf(
                "HO/2021/0015",
                "Purchase",
                "Upgrade of the core banking licence",
                "Pending with GM",
                "Internal",
                "HO-FIN",
                "2021-05-09",
                "",
                "",
                "Demo User",
                "user@demo.example",
            ),
        )

    private fun stationery(): List<Any> =
        listOf(
            "HO/2021/0007",
            "Expense",
            "Office stationery for the second half",
            "Approved",
            "Internal",
            "HO-FIN",
            "2021-06-02",
            "2021-06-08",
            "",
            "Demo User",
            "user@demo.example",
        )

    private val FILES: List<List<Any>> =
        listOf(
            listOf("HO/2019/0012", "2019/HO-2019-0012/memo.docx", "MAIN", "Memo", ""),
            listOf("HO/2019/0012", "2019/HO-2019-0012/quotation.pdf", "ATTACHMENT", "Quotation", ""),
            listOf("HO/2019/0047", "2019/HO-2019-0047/memo.doc", "MAIN", "Memo", ""),
            listOf("HO/2019/0047", "2019/HO-2019-0047/rent-schedule.xls", "ATTACHMENT", "Rent schedule", "Confidential"),
            listOf("HO/2020/0003", "2020/HO-2020-0003/scan.tif", "MAIN", "Signed memo (scan)", ""),
            listOf("HO/2020/0003", "2020/HO-2020-0003/annexes.zip", "ATTACHMENT", "Annexes", ""),
            // The folder holds memo.pdf: matched without regard to case, with a warning.
            listOf("HO/2020/0118", "2020/HO-2020-0118/Memo.PDF", "MAIN", "Memo", ""),
            listOf("HO/2021/0007", "2021/HO-2021-0007/memo.pdf", "MAIN", "Memo", ""),
            listOf("HO/2021/0009", "2021/HO-2021-0009/memo.pdf", "MAIN", "Memo", ""),
            listOf("HO/2021/0015", "2021/HO-2021-0015/memo.docx", "MAIN", "Memo", ""),
        )

    private val APPROVALS: List<List<Any>> =
        listOf(
            listOf(
                "HO/2019/0012",
                "1",
                "Demo Head of Credit",
                "head.credit@demo.example",
                "Head of Credit",
                "Recommended",
                LocalDate.parse("2019-02-14"),
                "",
            ),
            listOf("HO/2019/0012", "2", "M. Karim", "", "Managing Director", "Approved", LocalDate.parse("2019-02-21"), "Within budget."),
            // Demo User recommended the lease and the sponsorship: linked by email, they read both (within clearance).
            listOf("HO/2019/0047", "1", "Demo User", "user@demo.example", "Officer", "Recommended", LocalDate.parse("2019-07-08"), ""),
            listOf(
                "HO/2019/0047",
                "2",
                "Demo Head of Finance",
                "head.finance@demo.example",
                "Chief Financial Officer",
                "Approved",
                LocalDate.parse("2019-07-15"),
                "",
            ),
            listOf("HO/2020/0003", "1", "Demo User", "user@demo.example", "Officer", "Recommended", "2020-01-15", ""),
            listOf("HO/2020/0003", "2", "S. Chowdhury", "", "Head of Marketing", "Rejected", "2020-01-20", "Not in this year's plan."),
            listOf("HO/2020/0118", "1", "Demo Director", "director@demo.example", "Director", "Approved", "2020-06-18", ""),
        )

    private val GENERATOR =
        listOf(
            "Two 60 kVA standby generators for the Khulna branch, with installation and two years of servicing.",
            "Three quotations were received; the lowest compliant one is attached.",
        )
    private val QUOTATION =
        listOf(
            "Two 60 kVA diesel generators, canopy type, automatic transfer switch.",
            "Price BDT 2,350,000 each, installation BDT 180,000. Valid for 60 days.",
        )
    private val INSURANCE =
        listOf(
            "Cover for cash in transit raised from BDT 20 million to BDT 35 million per movement.",
            "Premium as quoted by the insurer; effective from 1 July 2020.",
        )

    private fun annexes(): ByteArray =
        LegacyFiles.zip(
            listOf(
                "annex-1-budget.txt" to "Sponsorship budget: BDT 1,200,000 for eight matches.\n",
                "annex-2-league.txt" to "Regional cricket league, Khulna division, 2020 season.\n",
            ),
        )

    private const val CREATED = "2021-06-30T00:00:00Z"
    private const val THUMBS_BYTES = 64
    private const val SCAN_PAGES = 3
}
