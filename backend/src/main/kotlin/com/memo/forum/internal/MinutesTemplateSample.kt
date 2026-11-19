package com.memo.forum.internal

import com.memo.forum.MinutesTemplateTags
import com.memo.templating.ControlMark
import com.memo.templating.TemplateData
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A sample meeting for the preview of a minutes template (flow AI): every tag of [MinutesTemplateTags] has a value, three
 * items show a resolution with conditions and a vote, a noted item with a declaration, and a deferred item, each in its
 * content control as real minutes are (ADR-0066). Invented people and figures; English, like tenant content.
 */
internal object MinutesTemplateSample {
    private const val DEFERRED_ITEM = 3

    fun data(today: LocalDate): TemplateData {
        val date = today.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH))
        val values =
            mapOf(
                "meeting.committee" to "Board Risk Committee",
                "meeting.label" to "BRC 3/${today.year}",
                "meeting.kind" to "Ordinary meeting",
                "meeting.date" to date,
                "meeting.start" to "10:00",
                "meeting.end" to "12:30",
                "meeting.zone" to "Asia/Dhaka",
                "meeting.place" to "Board room, head office",
                "meeting.mode" to "In person and online",
                "meeting.chair" to "Dr A. Rahman",
                "meeting.closedAt" to "12:25",
                "minutes.status" to "DRAFT",
                "minutes.approvedOn" to "",
                "minutes.approvedBy" to "",
                "quorum.opening" to "The meeting was quorate (5 of 7 voting members present, 4 needed).",
            )
        check(values.keys == MinutesTemplateTags.MEETING.keys) { "the sample covers every meeting tag" }
        val rows =
            mapOf(
                "present" to
                    listOf(
                        row("name" to "Dr A. Rahman", "role" to "Chair", "mode" to "In person", "from" to "10:00", "until" to "12:25"),
                        row("name" to "Ms S. Karim", "role" to "Member", "mode" to "Online", "from" to "10:00", "until" to "12:25"),
                        row("name" to "Mr T. Hossain", "role" to "Member", "mode" to "In person", "from" to "10:20", "until" to "12:25"),
                    ),
                "alternates" to listOf(row("name" to "Mr J. Ahmed", "forMember" to "Mr K. Islam", "mode" to "In person")),
                "inAttendance" to listOf(row("name" to "Ms N. Chowdhury", "capacity" to "Chief Risk Officer", "items" to "1, 2")),
                "apologies" to listOf(row("name" to "Mr K. Islam", "alternate" to "Mr J. Ahmed")),
                "absent" to listOf(row("name" to "Mr R. Uddin")),
                "quorumChanges" to
                    listOf(row("at" to "11:40", "item" to "2", "text" to "Mr T. Hossain left; the meeting remained quorate.")),
                "resolutions" to
                    listOf(
                        row(
                            "number" to "BRC/${today.year}/014",
                            "item" to "1",
                            "outcome" to "APPROVED WITH CONDITIONS",
                            "text" to "The facility is approved.",
                        ),
                    ),
            )
        check(rows.keys == MinutesTemplateTags.LOOPS.keys) { "the sample covers every loop" }
        val items = listOf(approved(today), noted(), deferred())
        return TemplateData(values, rows, blocks = mapOf(MinutesTemplateTags.ITEMS to items))
    }

    private fun approved(today: LocalDate) =
        item(
            1,
            "Credit facility for a manufacturing borrower",
            "item.purpose" to "For decision",
            "item.presenters" to "Ms N. Chowdhury, Chief Risk Officer",
            "item.papers" to "Credit memorandum — circulated in advance",
            "item.notes" to "The committee discussed the borrower's exposure, the security offered and the pricing.",
            "item.outcome" to "APPROVED WITH CONDITIONS",
            "item.resolutionNumber" to "BRC/${today.year}/014",
            "item.resolutionText" to "It was RESOLVED that the facility be approved.",
            "item.conditions" to "1. Security to be perfected before the first drawdown.\n2. Quarterly monitoring report to the committee.",
            "item.validUntil" to "90 days from today",
            "item.vote" to "For 4, against 1, abstaining 0",
            "item.dissent" to "Ms S. Karim asked that her dissent be recorded: the tenor is too long.",
            "item.quorum" to "The meeting remained quorate (5 of 7 voting members, 4 needed).",
        )

    private fun noted() =
        item(
            2,
            "Quarterly risk report",
            "item.purpose" to "For noting",
            "item.papers" to "Risk dashboard — tabled at the meeting",
            "item.late" to "Tabled with the chair's agreement.",
            "item.declarations" to
                "Mr T. Hossain declared an interest (a family member is employed by a reporting unit) and left the meeting for this item.",
            "item.notes" to "The committee noted the report.",
            "item.outcome" to "NOTED",
            "item.tookNoPart" to "Mr T. Hossain took no part in this item.",
            "item.quorum" to "The meeting remained quorate (4 of 7 voting members, 4 needed).",
            "item.departures" to "Mr T. Hossain left the meeting during this item.",
        )

    private fun deferred() =
        item(
            DEFERRED_ITEM,
            "Revised liquidity policy",
            "item.purpose" to "For decision",
            "item.notes" to "The committee asked for the stress-test results.",
            "item.outcome" to "DEFERRED",
            "item.quorum" to "The meeting remained quorate (5 of 7 voting members, 4 needed).",
            "item.arrivals" to "Mr T. Hossain rejoined the meeting during this item.",
        )

    /** An item with every item tag: those not given are blank, so their blocks disappear as in real minutes. */
    private fun item(
        number: Int,
        title: String,
        vararg values: Pair<String, String>,
    ): TemplateData {
        val given = mapOf("item.number" to number.toString(), "item.title" to title, "item.section" to "Matters for decision") + values
        return TemplateData(
            values = MinutesTemplateTags.ITEM.keys.associateWith { given[it].orEmpty() },
            control = ControlMark("memo-item:sample-$number", "Item $number"),
        )
    }

    private fun row(vararg fields: Pair<String, String>): Map<String, String> {
        val given = fields.toMap()
        return given + listOf("note", "from", "until").filter { it !in given }.associateWith { "" }
    }
}
