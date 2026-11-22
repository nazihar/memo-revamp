package com.memo.search

import com.memo.common.authz.Permission
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.ratelimit.RateLimit
import com.memo.common.ratelimit.RateLimiter
import com.memo.common.tenant.TenantContext
import com.memo.meeting.internal.ItemChange
import com.memo.meeting.internal.MeetingRow
import com.memo.minutes.DecisionPlanting
import com.memo.minutes.ReleaseFixture
import com.memo.minutes.internal.DecisionStore
import com.memo.minutes.internal.NoteStore
import com.memo.minutes.internal.ResolutionNumbers
import com.memo.search.internal.decision.DecisionExportRequest
import com.memo.search.internal.decision.DecisionExports
import com.memo.search.internal.decision.DecisionIndex
import com.memo.search.internal.decision.DecisionRegister
import com.memo.search.internal.decision.RegisterExportFormat
import com.memo.testsupport.Audit
import com.memo.testsupport.Person
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The register's export for auditors (ADR-0069, design E08 §1.5; test plan E08 T3 `DecisionExportIT`; flow AT): the
 * spreadsheet's three sheets and their columns, every value a string cell with the formula prefix, restricted lines as
 * "Restricted item" with their line's fields only, the number check (first to last, in force, void, missing, and a void's
 * reason only where the decision is seen in full); the PDF's text (the about block, a table per committee, the check, the
 * footer on every page, landscape); who may export (officers and oversight; members and others 403); the per-person limit
 * counted after the 403 and 422; `TOO_MANY_ROWS` for both formats; the audit written before the file, with `rowsSha256`
 * equal to a recomputation over the register's rows.
 */
class DecisionExportIT : ReleaseFixture() {
    @Autowired lateinit var decisionIndex: DecisionIndex

    @Autowired lateinit var decisionStore: DecisionStore

    @Autowired lateinit var noteStore: NoteStore

    @Autowired lateinit var numbers: ResolutionNumbers

    @Autowired lateinit var limiter: RateLimiter

    @Autowired lateinit var exports: DecisionExports

    private fun export(
        person: Person,
        body: String,
    ): MvcTestResult = post("/api/v1/decision-register/export", person.session, body)

    /** The planted meeting: a loan, an item whose title begins with `=`, and one restricted to [colleague] with its title hidden. */
    private inner class Planted {
        val meeting: MeetingRow = meeting()
        val loan = addItem(meeting, "Term loan, Karnaphuli Steel")
        val formula = addItem(meeting, "=HYPERLINK(\"https://example.test\",\"open\") facility")
        val named = addItem(meeting, "Settlement with a counterparty")
        val decisions: Map<UUID, UUID>

        init {
            change(meeting, named, ItemChange("Settlement with a counterparty", restrictedTo = listOf(colleague.id), titleHidden = true))
            publish(meeting)
            val planting = DecisionPlanting(transactions, decisionStore, noteStore)
            decisions =
                linkedMapOf(
                    loan to planting.decision(tenant.id, meeting.id, brc, loan, secretary.id, "@SUM(1+1) It was resolved to lend."),
                    formula to planting.decision(tenant.id, meeting.id, brc, formula, secretary.id, "Approved the facility."),
                    named to
                        planting.decision(
                            tenant.id,
                            meeting.id,
                            brc,
                            named,
                            secretary.id,
                            "Settled with the counterparty for BDT 5 million.",
                            conditions = listOf("+Release the guarantee"),
                        ),
                )
            decisions.values.forEach { planting.number(tenant.id, it, decisionReads, numbers) }
            // Two numbers made void, as a correction would: the formula item's (seen by oversight) and the named item's.
            void(decisions.getValue(formula), "Recorded against the wrong item")
            void(decisions.getValue(named), "Wrong counterparty named in the record")
            transactions.inTenant(tenant.id) { decisions.values.forEach { check(decisionIndex.put(it)) } }
        }

        private fun void(
            decision: UUID,
            reason: String,
        ) = transactions.inTenant(tenant.id) {
            val number = checkNotNull(resolutionStore.inForce(decision))
            check(resolutionStore.void(number.id, reason, Instant.now().truncatedTo(ChronoUnit.MICROS), 2))
        }

        fun number(item: UUID): String =
            checkNotNull(RegisterTable.row(tenant.id.value, decisions.getValue(item))?.get("resolution_number")) as String
    }

    @Test
    fun `the spreadsheet holds the decisions, the about sheet and the number check, every value a string`() {
        val p = Planted()
        val exported = export(reader, """{"format":"XLSX","forumIds":["$brc"]}""")
        assertThat(exported).hasStatusOk()
        assertThat(exported.response.contentType).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        assertThat(exported.response.getHeader("Content-Disposition")).contains("attachment", "decision-register.xlsx")
        assertThat(exported.response.getHeader("Cache-Control")).isEqualTo("no-store")
        val book = sheets(exported)
        assertThat(book.keys).containsExactly("Decisions", "About this export", "Number check")

        val decisions = book.getValue("Decisions")
        assertThat(decisions.first()).startsWith("Committee", "Meeting", "Meeting date", "Item", "Title", "Outcome", "Resolution number")
        assertThat(decisions).hasSize(4)
        val byNumber = decisions.drop(1).associateBy { it[6] }
        val loan = byNumber.getValue(p.number(p.loan))
        assertThat(loan[0] to loan[4]).isEqualTo("Board Risk Committee" to "Term loan, Karnaphuli Steel")
        // Values a spreadsheet would read as a formula carry the injection prefix.
        assertThat(loan[8]).isEqualTo("'@SUM(1+1) It was resolved to lend.")
        assertThat(byNumber.getValue(p.number(p.formula))[4]).isEqualTo("'=HYPERLINK(\"https://example.test\",\"open\") facility")
        assertThat(byNumber.getValue(p.number(p.formula))[7]).isEqualTo("Void")
        // The named item is a restricted line for oversight: its number, meeting and item only.
        val line = byNumber.getValue(p.number(p.named))
        assertThat(line[4]).isEqualTo("Restricted item: restricted to named members")
        assertThat(line.slice(listOf(5, 8, 9, 10, 12, 13, 14, 15, 16))).allSatisfy { assertThat(it).isEmpty() }
        assertThat(line[7]).isEqualTo("Void")
        assertThat(decisions.flatten().joinToString(" ")).doesNotContain("counterparty", "Release the guarantee")
        assertThat(loan.last()).endsWith("/meetings/${p.meeting.id}")

        val about = book.getValue("About this export").filter { it.isNotEmpty() }.associate { it.first() to it.getOrElse(1) { "" } }
        assertThat(about["Produced by"]).isEqualTo(reader.account.displayName)
        assertThat(about["Committees"]).isEqualTo("Board Risk Committee")
        assertThat(about["Meeting dates"]).isEqualTo("Any")
        assertThat(about["Decisions"]).isEqualTo("3")
        assertThat(about["Restricted items"]).isEqualTo("1. Restricted items show their number only.")
        assertThat(about.keys).contains("Produced at (UTC)", "What a restricted item is", "Order")
        assertThat(about.keys.filter { it.startsWith("Produced at (") }).describedAs("in UTC and the tenant's zone").hasSize(2)

        val check = book.getValue("Number check")
        val period =
            LocalDate
                .now()
                .plusDays(DAYS_AHEAD)
                .year
                .toString()
        assertThat(check[1]).containsExactly("Board Risk Committee", period, "1", "3", "1", "2", "None", "None", "None", "Yes")
        val voids = check.dropWhile { it.firstOrNull() != "Void numbers" }.drop(2).associate { it[2] to it[4] }
        assertThat(voids)
            .containsEntry(p.number(p.formula), "Recorded against the wrong item")
            .containsEntry(p.number(p.named), "Reason not shown: restricted item")
        assertThat(check.flatten().joinToString(" ")).doesNotContain("Wrong counterparty")

        // The secretary sees the named item in full: its title, text and the void's reason.
        val secretarys = sheets(export(secretary, """{"format":"XLSX"}"""))
        assertThat(secretarys.getValue("Decisions").flatten()).contains("Settlement with a counterparty", "1. +Release the guarantee")
        assertThat(secretarys.getValue("Number check").flatten()).contains("Wrong counterparty named in the record")
    }

    @Test
    fun `the PDF holds the about block, a table per committee in date order, the number check and the footer on every page`() {
        val p = Planted()
        val later = meeting(days = DAYS_AHEAD + 7)
        val renewal = addItem(later, "Facility renewal, Acme")
        publish(later)
        val planting = DecisionPlanting(transactions, decisionStore, noteStore)
        val renewed = planting.decision(tenant.id, later.id, brc, renewal, secretary.id, "Renewed for a year.")
        transactions.inTenant(tenant.id) { check(decisionIndex.put(renewed)) }

        val exported = export(reader, """{"format":"PDF","sort":"NEWEST"}""")
        assertThat(exported).hasStatusOk()
        assertThat(exported.response.contentType).isEqualTo("application/pdf")
        assertThat(exported.response.getHeader("Content-Disposition")).contains("attachment", "decision-register.pdf")
        Loader.loadPDF(exported.response.contentAsByteArray).use { pdf ->
            val box = pdf.getPage(0).mediaBox
            assertThat(box.width).isGreaterThan(box.height)
            // In drawing order (each cell's lines together); the table is in date order whatever the order asked.
            val stripper = PDFTextStripper()
            val text = stripper.getText(pdf).replace(Regex("\\s+"), " ")
            val expected =
                listOf(
                    "Register of committee decisions",
                    "About this export",
                    "Board Risk Committee",
                    "Term loan, Karnaphuli Steel",
                    "Facility renewal, Acme",
                    "Number check",
                    "Void numbers",
                    "Recorded against the wrong item",
                )
            assertThat(expected.map { text.indexOf(it) }).allSatisfy { assertThat(it).isNotNegative() }.isSorted()
            assertThat(text).contains("Restricted item: restricted to named members", "=HYPERLINK", p.number(p.named))
            assertThat(text).doesNotContain("Settlement with a counterparty", "Settled with the counterparty", "Wrong counterparty")
            (1..pdf.numberOfPages).forEach { page ->
                stripper.startPage = page
                stripper.endPage = page
                assertThat(
                    stripper.getText(pdf),
                ).contains("Page $page of ${pdf.numberOfPages}", "Produced by ${reader.account.displayName}")
            }
        }
        Audit.assertAudited(tenant.id, "search.decisions.exported", reader.id.toString(), reader.id.toString())
    }

    @Test
    fun `officers and oversight export, members and everyone else are 403, and an officer exports their committees only`() {
        Planted()
        val other = otherCommittee()
        val manager = person().also { asUser(admin) { grants.grantCustom(it.id, "Committee managers", setOf(Permission.FORUMS_MANAGE)) } }
        listOf(secretary, chair, manager, reader).forEach { person ->
            assertThat(export(person, """{"format":"XLSX"}""")).describedAs("${person.id}").hasStatusOk()
        }
        listOf(director, colleague, guest, outsider).forEach { person ->
            val refused = export(person, """{"format":"XLSX"}""")
            assertThat(refused).hasStatus(HttpStatus.FORBIDDEN)
            assertThat(refused.response.contentAsString).doesNotContain("Karnaphuli")
        }
        // An officer's committees are their export's: another committee is out of its scope; oversight's covers it.
        val outOfScope = export(secretary, """{"format":"PDF","forumIds":["$other"]}""")
        assertThat(outOfScope).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(outOfScope.errors()).containsExactly("forumIds:FORUM_OUT_OF_SCOPE")
        assertThat(export(reader, """{"format":"PDF","forumIds":["$other"]}""")).hasStatusOk()
        val about =
            sheets(export(chair, """{"format":"XLSX"}""")).getValue("About this export").filter { it.isNotEmpty() }.associate {
                it.first() to
                    it.getOrElse(1) { "" }
            }
        assertThat(about["Committees"]).isEqualTo("Board Risk Committee")
        // A format the API does not know is an unreadable body.
        assertThat(export(reader, """{"format":"CSV"}""")).hasStatus(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `five exports an hour for one person, counted after the 403 and the 422`() {
        Planted()
        repeat(6) {
            assertThat(export(secretary, """{"format":"XLSX","text":"${"a".repeat(201)}"}""")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
            assertThat(export(director, """{"format":"XLSX"}""")).hasStatus(HttpStatus.FORBIDDEN)
        }
        assertThat(export(secretary, """{"format":"XLSX"}""")).hasStatusOk()
        repeat(4) { limiter.consume(RateLimit.REGISTER_EXPORT_PER_USER, "${tenant.id}:${secretary.id}") }
        val refused = export(secretary, """{"format":"PDF"}""")
        assertThat(refused).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(refused.response.getHeader("Retry-After")).isNotNull()
        // Another person has their own allowance.
        assertThat(export(chair, """{"format":"PDF"}""")).hasStatusOk()
    }

    @Test
    fun `the audit is written before the file, with the filters, the counts and the rows' hash`() {
        val p = Planted()
        val request =
            DecisionExportRequest(RegisterExportFormat.XLSX, text = "  Karnaphuli   Steel ", forumIds = listOf(brc), resolutionsOnly = true)
        // The export's transaction audits it: the entry is there before any file is written.
        val export =
            ActorContext.call(ActorScope(Actor.User(reader.id), null)) {
                TenantContext.call(tenant.id) { transactions.inTenant(tenant.id) { exports.export(request) } }
            }
        assertThat(export.rows.map { it.row.decisionId }).containsExactly(p.decisions.getValue(p.loan))
        val entry = Audit.assertAudited(tenant.id, "search.decisions.exported", reader.id.toString(), reader.id.toString())
        val hash = DecisionRegister.sha256(listOf("${p.decisions.getValue(p.loan)}:1").sorted().joinToString("\n"))
        assertThat(entry.canonical)
            .contains(
                "\"format\":\"XLSX\"",
                "\"rows\":1",
                "\"lines\":0",
                "\"rowsSha256\":\"$hash\"",
                brc.toString(),
                "\"resolutionsOnly\":true",
            ).contains(DecisionRegister.sha256("karnaphuli steel"), "textSha256")
            .doesNotContain("Karnaphuli", "karnaphuli")

        // Over HTTP: every row of the register, recomputed from the table as its owner.
        assertThat(export(secretary, """{"format":"PDF","forumIds":["$brc"]}""")).hasStatusOk()
        val all =
            RegisterTable
                .rows(tenant.id.value)
                .map { "${it["decision_id"]}:${it["entry_seq"]}" }
                .sorted()
                .joinToString("\n")
        val audited = Audit.assertAudited(tenant.id, "search.decisions.exported", secretary.id.toString(), secretary.id.toString())
        assertThat(audited.canonical).contains("\"format\":\"PDF\"", "\"rows\":3", "\"rowsSha256\":\"${DecisionRegister.sha256(all)}\"")
    }

    @Test
    fun `more rows than a format holds are 422 TOO_MANY_ROWS, with the count`() {
        val type = asUser(admin) { forums.get(brc) }.typeId
        // 1,334 meetings of 15 decisions: 20,010 rows, every 40th above the default clearance (the officer sees the rest).
        DecisionVolume.committee(tenant.id.value, brc, type, "BRC", MEETINGS, colleague.id)
        DecisionVolume.analyse()
        val manager =
            person(clearance = 2).also {
                asUser(admin) { grants.grantCustom(it.id, "Committee managers", setOf(Permission.FORUMS_MANAGE)) }
            }

        val spreadsheet = export(manager, """{"format":"XLSX"}""")
        assertThat(spreadsheet).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(spreadsheet.errors()).containsExactly("format:TOO_MANY_ROWS")
        assertThat(spreadsheet.json()["maximum"].asInt() to spreadsheet.json()["moreThan"].asBoolean()).isEqualTo(20_000 to true)
        val pdf = export(manager, """{"format":"PDF"}""")
        assertThat(pdf.errors()).containsExactly("format:TOO_MANY_ROWS")
        assertThat(pdf.json()["maximum"].asInt()).isEqualTo(2_000)

        // 134 meetings (2,010 rows): too many for a PDF, with the exact count; a spreadsheet holds them all.
        val to = LocalDate.parse("2017-01-15").plusDays(133L * 30)
        val narrowed = """"forumIds":["$brc"],"to":"$to""""
        val tooMany = export(manager, """{"format":"PDF",$narrowed}""")
        assertThat(tooMany).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(tooMany.json()["rows"].asInt() to tooMany.json()["moreThan"].asBoolean()).isEqualTo(2_010 to false)
        val held = export(manager, """{"format":"XLSX",$narrowed}""")
        assertThat(held).hasStatusOk()
        assertThat(sheets(held).getValue("Decisions")).hasSize(2_011)
    }

    /** Every sheet of the workbook by name, as rows of string cells (every cell must be a string). */
    private fun sheets(result: MvcTestResult): Map<String, List<List<String>>> {
        assertThat(result).hasStatusOk()
        return XSSFWorkbook(ByteArrayInputStream(result.response.contentAsByteArray)).use { book ->
            (0 until book.numberOfSheets).associate { i ->
                val sheet = book.getSheetAt(i)
                sheet.sheetName to
                    (0..sheet.lastRowNum).map { r ->
                        val row = sheet.getRow(r) ?: return@map emptyList()
                        (0 until row.lastCellNum.coerceAtLeast(0)).map { c ->
                            val cell = row.getCell(c)
                            if (cell != null) assertThat(cell.cellType).isEqualTo(CellType.STRING)
                            cell?.stringCellValue.orEmpty()
                        }
                    }
            }
        }
    }

    private companion object {
        const val MEETINGS = 1_334
    }
}
