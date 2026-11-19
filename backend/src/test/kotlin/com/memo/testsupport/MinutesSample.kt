package com.memo.testsupport

import com.memo.templating.ControlMark
import com.memo.templating.TemplateData
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph

/**
 * A minutes template and its data for the E06 spike (ADR-0066, test plan T1): a heading, an attendance table loop, the
 * `items` block (heading, a declarations section, the key points, a vote table loop, the outcome) and a closing line.
 * Item 5's discussion carries [canary], which a reader's copy must never contain once item 5 is replaced.
 */
object MinutesSample {
    const val CANARY = "CANARY-ITEM-5-DISCUSSION"

    fun template(): ByteArray =
        Docx.document { d ->
            bold(d.createParagraph(), "Minutes of the {{meeting.label}}")
            d.createParagraph().createRun().setText("Held on {{meeting.date}} at {{meeting.place}}")
            val table = d.createTable(2, 2)
            table.getRow(0).getCell(0).text = "Present{{present}}"
            table.getRow(0).getCell(1).text = "Role"
            table.getRow(1).getCell(0).text = "[name]"
            table.getRow(1).getCell(1).text = "[role]"
            d.createParagraph().createRun().setText("{{?items}}")
            bold(d.createParagraph(), "{{item.number}}. {{item.title}}")
            d.createParagraph().createRun().setText("{{?item.declarations}}")
            d.createParagraph().createRun().setText("{{item.declarations}}")
            d.createParagraph().createRun().setText("{{/item.declarations}}")
            d.createParagraph().createRun().setText("Key points discussed included:")
            d.createParagraph().createRun().setText("{{item.notes}}")
            val votes = d.createTable(2, 2)
            votes.getRow(0).getCell(0).text = "Vote{{item.votes}}"
            votes.getRow(0).getCell(1).text = "Members"
            votes.getRow(1).getCell(0).text = "[side]"
            votes.getRow(1).getCell(1).text = "[count]"
            bold(d.createParagraph(), "{{item.outcome}} ({{meeting.code}})")
            d.createParagraph().createRun().setText("{{/items}}")
            d.createParagraph().createRun().setText("There being no other business, the meeting closed at {{meeting.closedAt}}.")
        }

    /** The data for [items] agenda items; with [controls], each item's block is wrapped in its control. */
    fun data(
        items: Int = 9,
        controls: Boolean = true,
        notes: (Int) -> String = ::notes,
    ): TemplateData =
        TemplateData(
            values =
                mapOf(
                    "meeting.label" to "Board Risk Committee 4/2026",
                    "meeting.code" to "BRC",
                    "meeting.date" to "19 November 2026",
                    "meeting.place" to "Board room, head office",
                    "meeting.closedAt" to "12:40",
                ),
            rows =
                mapOf(
                    "present" to
                        listOf(
                            mapOf("name" to "Demo Chair", "role" to "Chair"),
                            mapOf("name" to "Demo Director", "role" to "Member"),
                        ),
                ),
            blocks = mapOf("items" to (1..items).map { item(it, controls, notes(it)) }),
        )

    /** [words] words of varied prose (seeded), so the package compresses like real minutes rather than like a zip bomb. */
    fun prose(
        words: Int,
        seed: Int,
    ): String {
        val random = java.util.Random(seed.toLong())
        return (1..words).joinToString(" ") { i ->
            val word = WORDS[random.nextInt(WORDS.size)] + if (random.nextInt(4) == 0) random.nextInt(1000).toString() else ""
            if (i % 15 == 0) "$word." else word
        }
    }

    private val WORDS =
        (
            "the committee discussed exposure security pricing covenant borrower facility limit tenor collateral margin " +
                "liquidity capital provision default rating sector growth deposit branch audit risk policy board approve " +
                "management report quarter annual review compliance regulator interest rate currency loan portfolio"
        ).split(' ')

    fun tag(n: Int) = "memo-item:${itemId(n)}"

    fun itemId(n: Int) = "0190a000-0000-7000-8000-%012d".format(n)

    fun notes(n: Int): String =
        if (n == 5) "The committee discussed the borrower's exposure. $CANARY" else "The committee noted the paper on item $n."

    private fun item(
        n: Int,
        controls: Boolean,
        notes: String,
    ) = TemplateData(
        values =
            mapOf(
                "item.number" to n.toString(),
                "item.title" to "Agenda item $n",
                "item.declarations" to if (n == 5) "Demo Director declared an interest and left the meeting for this item." else "",
                "item.notes" to notes,
                "item.outcome" to if (n % 2 == 0) "NOTED" else "APPROVED",
            ),
        rows = mapOf("item.votes" to listOf(mapOf("side" to "For", "count" to "${n + 1}"), mapOf("side" to "Against", "count" to "0"))),
        control = if (controls) ControlMark(tag(n), "Item $n") else null,
    )

    private fun bold(
        p: XWPFParagraph,
        text: String,
    ) {
        p.createRun().apply { isBold = true }.setText(text)
    }

    /** The document's text (body, headers, footers), controls included. */
    fun text(docx: ByteArray): String = Docx.text(docx)

    /** The text of each body-level control, by tag, as POI reads it. */
    fun controlTexts(docx: ByteArray): Map<String, String> =
        XWPFDocument(docx.inputStream()).use { d ->
            d.document.body.sdtList.associate { sdt ->
                sdt.sdtPr.tag.`val` to sdt.sdtContent.newCursor().use { it.textValue }
            }
        }
}
