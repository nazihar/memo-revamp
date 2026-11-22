package com.memo.minutes.internal

import com.memo.forum.MinutesApproval
import com.memo.memo.RecordPdfMetadata
import com.memo.memo.ReportDocument
import com.memo.memo.ReportSection
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Everything a version of the record, or a reader's approved copy of it, is made from (read in one transaction). */
data class RecordContext(
    val source: DraftSource,
    val row: MinutesRow,
    val record: RecordRow,
    val approval: ApprovalRow,
    /** The approver's name as the stamp prints it. */
    val approver: String,
    /** The meeting that approved them (AT_NEXT_MEETING): its label. */
    val approvingMeeting: String?,
)

/**
 * The generated pages of the approved minutes' record (ADR-0066), pure, in English, times in UTC: the cover (the committee,
 * the meeting, how and by whom the minutes were approved, the approved .docx's hash and the approval's, and for a reader's
 * copy the items redacted in it), the resolutions annex (each numbered resolution, an item the reader may not read as its
 * number and "restricted"), the stamp line printed on every page, and the text the record's check looks for.
 */
object MinutesRecordPages {
    private val TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC)
    private val DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)

    fun cover(
        c: RecordContext,
        set: RedactionSet,
    ): ReportDocument {
        val meeting = c.source.meeting
        val label = label(c)
        val sections =
            buildList {
                add(
                    ReportSection(
                        "The meeting",
                        lines =
                            listOf(
                                "Committee" to c.source.committee,
                                "Meeting" to label,
                                "Date" to meeting.startsAt.atZone(meeting.timeZone).format(DAY),
                                "Closed" to TIME.format(c.source.heldAt),
                            ),
                    ),
                )
                add(ReportSection("The approval", lines = approval(c)))
                if (set.redacted.isNotEmpty()) add(redactions(c, set))
                add(
                    ReportSection(
                        "About this record",
                        paragraphs =
                            listOf(
                                "This file is the record of the approved minutes: the approved document as PDF/A and the meeting's " +
                                    "resolutions, approved by the authenticated approval above and kept locked. Check it against " +
                                    "the hashes on the minutes page.",
                            ),
                    ),
                )
            }
        val title = if (set.redacted.isEmpty()) "Approved minutes" else "Approved minutes: your copy"
        return document(c, title, sections)
    }

    private fun approval(c: RecordContext): List<Pair<String, String>> {
        val a = c.approval
        val atMeeting = c.row.policy.approval == MinutesApproval.AT_NEXT_MEETING
        val how =
            when {
                atMeeting && c.approvingMeeting != null -> "At the meeting ${c.approvingMeeting}"
                atMeeting -> "At the next meeting"
                else -> "By circulation"
            }
        val factors = a.authentication["factors"]?.let { f -> (0 until f.size()).map { f.get(it).asString() } }.orEmpty()
        return listOf(
            "Approved" to how,
            "Confirmed by" to "${c.approver} (${role(a.role)})",
            "On" to TIME.format(a.at),
            "Authenticated with" to
                factors.joinToString(" and ") { FACTORS[it] ?: it.lowercase(Locale.ROOT) }.ifEmpty { "no recorded factor" },
            "Approved document" to "SHA-256 ${a.docxSha256}",
            "Approval record hash" to a.recordHash,
            "Previous approval" to (a.previousHash ?: "none (the committee's first)"),
            "Record version" to c.record.recordVersion.toString(),
        )
    }

    /** The items a reader's copy shows redacted, by number (and title where they may see it). */
    private fun redactions(
        c: RecordContext,
        set: RedactionSet,
    ): ReportSection {
        val items = c.source.items.associateBy { it.id }
        val lines =
            set.redacted.mapNotNull { r ->
                val item = items[r.itemId] ?: return@mapNotNull null
                val why =
                    when {
                        r.kind == RedactionKind.RECUSED && !item.titleHidden -> {
                            "${MinutesTemplateData.clean(item.title)}: you declared an interest (heading and decision only)"
                        }

                        r.kind == RedactionKind.RECUSED -> {
                            "You declared an interest (decision only)"
                        }

                        r.kind == RedactionKind.RESTRICTED && !item.titleHidden -> {
                            "${MinutesTemplateData.clean(item.title)}: restricted"
                        }

                        else -> {
                            "Restricted"
                        }
                    }
                "Item ${item.number.orEmpty()}" to why
            }
        return ReportSection(
            "This copy",
            paragraphs =
                listOf(
                    "This copy of the approved minutes is made for a reader who may not read every item: the items below " +
                        "are shown redacted. The full record is kept with the same approval.",
                ),
            lines = lines,
        )
    }

    /** The resolutions annex: each numbered resolution in agenda order, as [set]'s reader may read it. */
    fun annex(
        c: RecordContext,
        set: RedactionSet,
    ): ReportDocument {
        val kinds = set.redacted.associate { it.itemId to it.kind }
        val sections =
            numbered(c).map { (item, number) ->
                val kind = kinds[item.id]
                if (kind == RedactionKind.RESTRICTED || kind == RedactionKind.HIDDEN) {
                    ReportSection(number, paragraphs = listOf("Item ${item.number.orEmpty()} — restricted."))
                } else {
                    val entry = checkNotNull(c.source.decisions[item.id]).entry
                    val heading =
                        if (item.titleHidden &&
                            kind != null
                        ) {
                            "Item ${item.number.orEmpty()}"
                        } else {
                            "Item ${item.number}. ${item.title}"
                        }
                    ReportSection(
                        number,
                        paragraphs =
                            listOfNotNull(
                                MinutesTemplateData.clean(heading),
                                entry.outcomeLabel.uppercase(Locale.ENGLISH),
                                entry.text?.takeIf { it.isNotBlank() }?.let(MinutesTemplateData::clean),
                            ) + entry.conditions.mapIndexed { i, cond -> "Condition ${i + 1}: ${MinutesTemplateData.clean(cond)}" } +
                                listOfNotNull(entry.validUntil?.let { "Valid until ${it.format(DAY)}." }),
                    )
                }
            }
        val empty = listOf(ReportSection("No resolutions", paragraphs = listOf("The meeting passed no numbered resolutions.")))
        return document(c, "Resolutions", sections.ifEmpty { empty })
    }

    /** The minuted items with a resolution number in force, in agenda order. */
    fun numbered(c: RecordContext): List<Pair<com.memo.meeting.AgendaItemRef, String>> =
        MinutesTemplateData.minuted(c.source.items).mapNotNull { item ->
            val decision = c.source.decisions[item.id] ?: return@mapNotNull null
            c.source.resolutions[decision.decision.id]?.let { item to it }
        }

    /** The line at the foot of every page: what this is and who approved it, with the approval's hash. */
    fun stamp(
        c: RecordContext,
        set: RedactionSet,
    ): String {
        val copy = if (set.redacted.isEmpty()) "" else " · a reader's copy (${set.redacted.size} redacted)"
        return "Approved minutes of ${label(
            c,
        )} · confirmed by ${c.approver} (${role(c.approval.role)}) on ${TIME.format(c.approval.at)} · " +
            "approval ${c.approval.recordHash.take(HASH_SHOWN)} · record version ${c.record.recordVersion}$copy"
    }

    /** What must come back from the record's text, in order: the cover's committee and meeting, then each resolution number. */
    fun textItems(c: RecordContext): List<String> = listOf(c.source.committee, label(c)) + numbered(c).map { it.second }

    fun metadata(c: RecordContext): RecordPdfMetadata =
        RecordPdfMetadata(
            title = "Minutes of ${label(c)}",
            identifier = label(c),
            description = "Approved document SHA-256 ${c.approval.docxSha256}; approval ${c.approval.recordHash}",
            author = c.source.committee,
            created = c.approval.at,
        )

    fun label(c: RecordContext): String =
        c.source.meeting.label ?: c.source.meeting.title ?: c.source.meeting.id
            .toString()

    /** [title] with the running header (the committee and meeting) and footer (the record version). */
    private fun document(
        c: RecordContext,
        title: String,
        sections: List<ReportSection>,
    ) = ReportDocument(
        title,
        "${c.source.committee} · ${label(c)}",
        "Approved minutes · record version ${c.record.recordVersion}",
        sections,
    )

    private fun role(role: MinutesRole) = if (role == MinutesRole.CHAIR) "chair" else "vice-chair"

    private const val HASH_SHOWN = 16
    private val FACTORS = mapOf("TOTP" to "authenticator", "RECOVERY_CODE" to "recovery code")
}
