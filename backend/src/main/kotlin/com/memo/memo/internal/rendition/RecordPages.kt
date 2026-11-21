package com.memo.memo.internal.rendition

import com.memo.memo.internal.NoteBlock
import com.memo.memo.internal.NoteBreak
import com.memo.memo.internal.NoteDocument
import com.memo.memo.internal.NoteInline
import com.memo.memo.internal.NoteList
import com.memo.memo.internal.NoteMemoRef
import com.memo.memo.internal.NoteMention
import com.memo.memo.internal.NoteParagraph
import com.memo.memo.internal.NoteText
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One decision's stamp as the record prints it (ADR-0051). */
data class StampLine(
    val round: Int,
    val person: String,
    val actingFor: String?,
    val decision: String,
    val at: Instant,
    val factors: List<String>,
    val secondFactorAt: Instant?,
    val documentNumber: Int?,
    val documentHash: String?,
    val recordHash: String,
    val previousHash: String?,
)

/**
 * A committee's decision on the memo as the record's approvals print it (ADR-0065): the committee, the outcome, the
 * resolution number when it has one, and the meeting.
 */
data class CommitteeDecisionLine(
    val round: Int,
    val committee: String,
    val outcome: String,
    val resolution: String?,
    val meeting: String?,
    val at: Instant,
)

/** One entry of the note sheet as the record prints it: a note, or an older action's plain remark. */
data class NoteLine(
    val round: Int,
    val author: String,
    val actingFor: String?,
    val label: String,
    val at: Instant,
    val body: NoteDocument,
    val corrected: Boolean,
    /** Memo references the record may not name (the memo is classified above this one), by memo id. */
    val restricted: Set<java.util.UUID> = emptySet(),
)

/** A part that stays outside the PDF, named by its hash (ADR-0053). */
data class PlaceholderLine(
    val title: String,
    val type: String,
    val sizeBytes: Long,
    val sha256: String,
    val classification: String,
    val reason: String,
)

/**
 * The generated pages of a final record (ADR-0052, ADR-0053) in English (§10 Q3): plain strings in the page model, times
 * in UTC so the record reads the same everywhere.
 */
object RecordPages {
    private val TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC)

    fun time(at: Instant): String = TIME.format(at)

    fun cover(
        header: String,
        footer: String,
        rows: List<Pair<String, String>>,
    ) = PageDocument(
        header,
        footer,
        listOf(
            Block.Title("Final record"),
            Block.KeyValues(rows),
            Block.Paragraph(
                "This file is the memo's record: the approved document, the approvals, the note sheet and the attachments, " +
                    "in one PDF/A file. Attachments that cannot be shown as PDF/A are kept as original files and named here " +
                    "by their SHA-256.",
                small = true,
            ),
        ),
    )

    fun approvals(
        header: String,
        footer: String,
        stamps: List<StampLine>,
        committees: List<CommitteeDecisionLine> = emptyList(),
    ): PageDocument {
        val blocks = mutableListOf<Block>(Block.Title("Approvals"))
        if (stamps.isEmpty() && committees.isEmpty()) blocks += Block.Paragraph("No decisions were recorded.")
        val rounds = (stamps.map { it.round } + committees.map { it.round }).toSortedSet()
        rounds.forEach { round ->
            blocks += Block.Heading("Round $round")
            val entries =
                stamps.filter { it.round == round }.map { it.at to stamp(it) } +
                    committees.filter { it.round == round }.map { it.at to committee(it) }
            entries.sortedBy { it.first }.forEach { blocks += it.second }
        }
        return PageDocument(header, footer, blocks)
    }

    /** "Board Risk Committee — Approved with conditions — Resolution BRC/2026/014", the meeting and the time. */
    private fun committee(line: CommitteeDecisionLine): Block {
        val head = listOfNotNull(line.committee, line.outcome, line.resolution?.let { "Resolution $it" }).joinToString(" — ")
        val rows = listOfNotNull(line.meeting?.let { "Meeting" to it }, line.resolution?.let { "Resolution" to it })
        return Block.Box(
            listOf(Block.Paragraph(listOf(Run(head, bold = true), Run("  ${time(line.at)}")))) +
                listOfNotNull(Block.KeyValues(rows).takeIf { rows.isNotEmpty() }),
        )
    }

    fun notes(
        header: String,
        footer: String,
        notes: List<NoteLine>,
    ): PageDocument {
        val blocks = mutableListOf<Block>(Block.Title("Note sheet"))
        if (notes.isEmpty()) blocks += Block.Paragraph("No notes were written.")
        notes.groupBy { it.round }.toSortedMap().forEach { (round, inRound) ->
            blocks += Block.Heading("Round $round")
            inRound.sortedBy { it.at }.forEach { blocks += note(it) }
        }
        return PageDocument(header, footer, blocks)
    }

    fun placeholder(
        header: String,
        footer: String,
        line: PlaceholderLine,
    ) = PageDocument(
        header,
        footer,
        listOf(
            Block.Title(line.title),
            Block.KeyValues(
                listOf(
                    "Type" to line.type,
                    "Size" to "${line.sizeBytes} bytes",
                    "SHA-256" to line.sha256,
                    "Classification" to line.classification,
                ),
            ),
            Block.Paragraph(line.reason),
        ),
    )

    private fun stamp(stamp: StampLine): Block {
        val who = stamp.actingFor?.let { "${stamp.person} for $it" } ?: stamp.person
        val age = stamp.secondFactorAt?.let { Duration.between(it, stamp.at).toMinutes() }
        val factors = stamp.factors.joinToString(" and ") { FACTORS[it] ?: it.lowercase() }.ifEmpty { "no recorded factor" }
        val authenticated = "Authenticated with $factors" + (age?.let { ", the second factor $it minutes before" } ?: "")
        val rows =
            listOfNotNull(
                "Authenticated" to authenticated,
                stamp.documentNumber?.let { n -> "Document" to "Version $n · SHA-256 ${stamp.documentHash.orEmpty()}" },
                "Record hash" to stamp.recordHash,
                "Previous record" to (stamp.previousHash ?: "none (first approval of the memo)"),
            )
        return Block.Box(
            listOf(
                Block.Paragraph(listOf(Run(who, bold = true), Run("  ${DECISIONS[stamp.decision] ?: stamp.decision} · ${time(stamp.at)}"))),
                Block.KeyValues(rows),
            ),
        )
    }

    private fun note(note: NoteLine): Block {
        val who = note.actingFor?.let { "${note.author} for $it" } ?: note.author
        val body = note.body.blocks.flatMap { blocks(it, note, 0) }
        return Block.Box(
            listOf(Block.Paragraph(listOf(Run(who, bold = true), Run("  ${note.label} · ${time(note.at)}")))) +
                body +
                listOfNotNull(Block.Paragraph("Corrected by a later note.", small = true).takeIf { note.corrected }),
        )
    }

    private fun blocks(
        block: NoteBlock,
        note: NoteLine,
        depth: Int,
    ): List<Block> =
        when (block) {
            is NoteParagraph -> {
                listOf(Block.Paragraph(runs(block.inlines, note), struck = note.corrected, indent = depth))
            }

            is NoteList -> {
                block.items.flatMapIndexed { i, item ->
                    val marker = if (block.ordered) "${(block.start ?: 1) + i}. " else "• "
                    val first = item.firstOrNull() as? NoteParagraph
                    val head =
                        Block.Paragraph(
                            listOf(Run(marker)) + runs(first?.inlines.orEmpty(), note),
                            struck = note.corrected,
                            indent =
                                depth + 1,
                        )
                    listOf(head) + item.drop(if (first != null) 1 else 0).flatMap { blocks(it, note, depth + 1) }
                }
            }
        }

    private fun runs(
        inlines: List<NoteInline>,
        note: NoteLine,
    ): List<Run> =
        inlines.map { inline ->
            when (inline) {
                is NoteText -> Run(inline.text + (inline.link?.let { " ($it)" } ?: ""), bold = inline.bold, italic = inline.italic)
                is NoteMention -> Run("@${inline.label}")
                is NoteMemoRef -> Run(if (inline.memoId in note.restricted) "Memo (restricted)" else inline.label)
                NoteBreak -> Run("\n")
            }
        }

    /** Factors named in words; others (a password) by their lower-cased code. */
    private val FACTORS = mapOf("TOTP" to "authenticator", "RECOVERY_CODE" to "recovery code")
    private val DECISIONS = mapOf("APPROVE" to "Approved", "RECOMMEND" to "Recommended", "RETURN" to "Returned", "REJECT" to "Rejected")
}
