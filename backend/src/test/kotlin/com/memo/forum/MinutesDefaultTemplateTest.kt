package com.memo.forum

import com.memo.forum.internal.MinutesTemplateSample
import com.memo.templating.internal.DocxTemplatesImpl
import com.memo.templating.internal.PoiDocxControls
import com.memo.templating.internal.PoiTlFiller
import com.memo.testsupport.Docx
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.apache.poi.xwpf.usermodel.ParagraphAlignment
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * The built-in minutes template (design E06 §1.7; test plan T2 `MinutesVocabularyTest`): shipped in English and Arabic,
 * scanned like any upload with no refusal and no problem, filled with the sample meeting into one locked control per
 * item. The files are written by [MinutesDefaultTemplates]; `:backend:updateMinutesTemplates` writes them again, and this
 * test fails while the shipped text differs from what the generator writes.
 */
class MinutesDefaultTemplateTest {
    private val templates = DocxTemplatesImpl()

    @ParameterizedTest
    @EnumSource(MinutesLanguage::class)
    fun `the built-in template is shipped as generated, scans clean and fills one control per item`(language: MinutesLanguage) {
        val generated = MinutesDefaultTemplates.write(language)
        val path = Path.of(System.getProperty("repo.root"), "backend/src/main/resources", MinutesTemplates.resource(language))
        val shipped =
            if (System.getProperty(UPDATE) == "true") {
                Files.createDirectories(path.parent)
                Files.write(path, generated)
                generated
            } else {
                MinutesTemplates().builtIn(language)
            }
        assertThat(Docx.text(shipped)).describedAs("run :backend:updateMinutesTemplates").isEqualTo(Docx.text(generated))

        val scan = templates.scan { SeekableInMemoryByteChannel(shipped) }
        assertThat(scan.refusals).isEmpty()
        assertThat(templates.check(scan, MinutesTemplateTags.vocabulary)).isEmpty()
        // E06 T7: no status or approval line (deprecated: the copies and the record are stamped instead).
        assertThat(templates.warnings(scan, MinutesTemplateTags.vocabulary)).isEmpty()
        assertThat(scan.tags.map { it.name }).doesNotContain("minutes.status", "minutes.approvedOn", "minutes.approvedBy")
        assertThat(scan.tags.map { it.name }).contains(MinutesTemplateTags.ITEMS, "item.notes", "present", "meeting.label")

        val filled = PoiTlFiller().use { it.fill(shipped.inputStream(), MinutesTemplateSample.data(LocalDate.of(2026, 11, 19))) }
        val controls = PoiDocxControls().scan(filled.inputStream())
        assertThat(controls.map { it.tag }).containsExactly("memo-item:sample-1", "memo-item:sample-2", "memo-item:sample-3")
        assertThat(controls).allSatisfy { assertThat(it.lock).isEqualTo("sdtLocked") }
        val text = Docx.text(filled)
        assertThat(text).contains("BRC 3/2026", "BRC/2026/014", "Dr A. Rahman").doesNotContain("{{", "[name]")
        // E07's actions: an item's in its block only (so a reader's copy that redacts the item drops them, E06 T7); the
        // meeting's own, on no item, in the meeting's table.
        assertThat(text.split(MinutesTemplateSample.ACTION_REFERENCE)).hasSize(2)
        assertThat(text.split(MinutesTemplateSample.MEETING_ACTION_REFERENCE)).hasSize(2)
        val redacted =
            PoiDocxControls().replace(
                filled.inputStream(),
                mapOf("memo-item:sample-1" to listOf(com.memo.templating.ControlParagraph("Item 1 — restricted"))),
            )
        assertThat(Docx.text(redacted))
            .doesNotContain(MinutesTemplateSample.ACTION_REFERENCE)
            .contains(MinutesTemplateSample.MEETING_ACTION_REFERENCE)
    }

    @Test
    fun `the Arabic template reads right to left`() {
        XWPFDocument(MinutesTemplates().builtIn(MinutesLanguage.AR).inputStream()).use { d ->
            assertThat(d.paragraphs).allSatisfy { assertThat(it.ctp.pPr?.bidi).isNotNull() }
            assertThat(d.tables).allSatisfy { assertThat(it.ctTbl.tblPr.bidiVisual).isNotNull() }
        }
    }

    private fun <T> PoiTlFiller.use(block: (PoiTlFiller) -> T): T =
        try {
            block(this)
        } finally {
            destroy()
        }

    private companion object {
        const val UPDATE = "minutes.templates.update"
    }
}

/**
 * Writes the built-in minutes template (design E06 §1.7): the committee and meeting, the DRAFT marker, the present, in
 * attendance and apologies tables, the quorum, then per item its heading, papers, declarations, the key points, the outcome
 * in bold capitals with the resolution, conditions, vote, dissent, abstentions, who took no part and the quorum; the
 * closing line and the chair's signature block. Each `{{?…}}` and `{{/…}}` is alone in its paragraph.
 */
object MinutesDefaultTemplates {
    private class Words(
        val minutesOf: String,
        val heldOn: String,
        val place: String,
        val chair: String,
        val present: List<String>,
        val inAttendance: List<String>,
        val apologies: List<String>,
        val quorum: String,
        val papers: String,
        val keyPoints: String,
        val resolution: String,
        val conditions: String,
        val validUntil: String,
        val closed: String,
        val chairSignature: String,
        val headings: List<String>,
        val itemActions: String,
        val actionsArising: String,
        /** Reference, action, owner, due (in an item's table and the meeting's own). */
        val actionColumns: List<String>,
    )

    private val ENGLISH =
        Words(
            minutesOf = "Minutes of the meeting {{meeting.label}}",
            heldOn = "Held on {{meeting.date}} at {{meeting.start}} ({{meeting.zone}}), {{meeting.mode}}",
            place = "Place: {{meeting.place}}",
            chair = "In the chair: {{meeting.chair}}",
            present = listOf("Present{{present}}", "Role", "Attendance"),
            inAttendance = listOf("In attendance{{inAttendance}}", "Capacity", "Items"),
            apologies = listOf("Apologies{{apologies}}", "Represented by"),
            quorum = "Quorum",
            papers = "Papers: {{item.papers}}",
            keyPoints = "Key points discussed included:",
            resolution = "Resolution {{item.resolutionNumber}}",
            conditions = "Conditions:",
            validUntil = "Valid until {{item.validUntil}}.",
            closed = "There being no other business, the meeting closed at {{meeting.closedAt}}.",
            chairSignature = "Chair",
            headings = listOf("Attendance", "Business of the meeting"),
            itemActions = "Actions agreed:",
            actionsArising = "Other actions arising",
            actionColumns = listOf("Reference", "Action", "Owner", "Due"),
        )

    private val ARABIC =
        Words(
            minutesOf = "محضر الاجتماع {{meeting.label}}",
            heldOn = "عُقد بتاريخ {{meeting.date}} الساعة {{meeting.start}} ({{meeting.zone}})، {{meeting.mode}}",
            place = "المكان: {{meeting.place}}",
            chair = "رئيس الاجتماع: {{meeting.chair}}",
            present = listOf("الحاضرون{{present}}", "الصفة", "طريقة الحضور"),
            inAttendance = listOf("المدعوون{{inAttendance}}", "الصفة", "البنود"),
            apologies = listOf("المعتذرون{{apologies}}", "ينوب عنه"),
            quorum = "النصاب",
            papers = "الأوراق: {{item.papers}}",
            keyPoints = "من أبرز ما نوقش:",
            resolution = "القرار رقم {{item.resolutionNumber}}",
            conditions = "الشروط:",
            validUntil = "ساري حتى {{item.validUntil}}.",
            closed = "ولعدم وجود أعمال أخرى، اختُتم الاجتماع الساعة {{meeting.closedAt}}.",
            chairSignature = "رئيس الاجتماع",
            headings = listOf("الحضور", "أعمال الاجتماع"),
            itemActions = "الإجراءات المتفق عليها:",
            actionsArising = "إجراءات أخرى مترتبة",
            actionColumns = listOf("المرجع", "الإجراء", "المسؤول", "تاريخ الاستحقاق"),
        )

    fun write(language: MinutesLanguage): ByteArray {
        val words = if (language == MinutesLanguage.AR) ARABIC else ENGLISH
        val rtl = language == MinutesLanguage.AR
        return Docx.document { d -> Builder(d, rtl).build(words) }
    }

    private class Builder(
        val d: XWPFDocument,
        val rtl: Boolean,
    ) {
        fun build(w: Words) {
            p("{{meeting.committee}}", bold = true, size = TITLE, center = true)
            p(w.minutesOf, bold = true, size = HEADING, center = true)
            p(w.heldOn)
            section("meeting.place") { p(w.place) }
            p(w.chair)
            p(w.headings[0], bold = true, size = HEADING)
            table(w.present, listOf("[name]", "[role]", "[mode]"))
            table(w.inAttendance, listOf("[name]", "[capacity]", "[items]"))
            table(w.apologies, listOf("[name]", "[alternate]"))
            p(w.quorum, bold = true)
            p("{{quorum.opening}}")
            p(w.headings[1], bold = true, size = HEADING)
            p("{{?items}}")
            p("{{item.number}}. {{item.title}}", bold = true)
            section("item.papers") { p(w.papers) }
            section("item.declarations") { p("{{item.declarations}}") }
            section("item.notes") {
                p(w.keyPoints)
                p("{{item.notes}}")
            }
            section("item.outcome") { p("{{item.outcome}}", bold = true) }
            section("item.resolutionNumber") { p(w.resolution, bold = true) }
            section("item.resolutionText") { p("{{item.resolutionText}}") }
            section("item.conditions") {
                p(w.conditions)
                p("{{item.conditions}}")
            }
            section("item.validUntil") { p(w.validUntil) }
            listOf("item.vote", "item.dissent", "item.abstentions", "item.tookNoPart", "item.ratification", "item.quorum").forEach {
                section(it) { p("{{$it}}") }
            }
            // E07's actions on the item (design E07 §11), shown only when there are some.
            section("item.actionCount") {
                p(w.itemActions)
                table(
                    listOf(w.actionColumns[0] + "{{item.actions}}") + w.actionColumns.drop(1),
                    listOf("[reference]", "[action]", "[owner]", "[due]"),
                )
            }
            p("{{/items}}")
            section("actions.count") {
                p(w.actionsArising, bold = true)
                table(
                    listOf(w.actionColumns[0] + "{{actions}}") + w.actionColumns.drop(1),
                    listOf("[reference]", "[action]", "[owner]", "[due]"),
                )
            }
            p(w.closed)
            p("")
            p("________________________________")
            p(w.chairSignature, bold = true)
        }

        /** A block shown only when [tag] is not blank, each end alone in its paragraph. */
        fun section(
            tag: String,
            body: () -> Unit,
        ) {
            p("{{?$tag}}")
            body()
            p("{{/$tag}}")
        }

        fun p(
            text: String,
            bold: Boolean = false,
            size: Int? = null,
            center: Boolean = false,
        ): XWPFParagraph =
            d.createParagraph().also { para ->
                direction(para)
                if (center) para.alignment = ParagraphAlignment.CENTER
                if (text.isNotEmpty()) run(para, text, bold, size)
            }

        /** A table: [header] (the loop tag in its first cell) and one row of `[fields]` below. */
        fun table(
            header: List<String>,
            fields: List<String>,
        ) {
            val table = d.createTable(2, header.size)
            if (rtl) (table.ctTbl.tblPr ?: table.ctTbl.addNewTblPr()).addNewBidiVisual()
            listOf(header, fields).forEachIndexed { r, texts ->
                texts.forEachIndexed { c, text ->
                    val cell = table.getRow(r).getCell(c)
                    val para = cell.paragraphs.first()
                    direction(para)
                    run(para, text, bold = r == 0, size = null)
                }
            }
            p("")
        }

        private fun direction(para: XWPFParagraph) {
            if (rtl) (para.ctp.pPr ?: para.ctp.addNewPPr()).addNewBidi()
        }

        private fun run(
            para: XWPFParagraph,
            text: String,
            bold: Boolean,
            size: Int?,
        ) {
            val run = para.createRun()
            run.isBold = bold
            size?.let { run.fontSize = it }
            if (rtl) (run.ctr.rPr ?: run.ctr.addNewRPr()).addNewRtl()
            run.setText(text)
        }
    }

    private const val TITLE = 16
    private const val HEADING = 13
}
