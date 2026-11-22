package com.memo.minutes.internal

import com.memo.memo.RecordPdfMetadata
import com.memo.memo.ReportDocument
import com.memo.memo.ReportSection
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The page of a certified resolution extract (design E06 §1.6.6, flow AC3), pure, in English, times in UTC: the committee,
 * the meeting and its date, the item's number (and title where every reader may read it), the resolution's number,
 * outcome, text, conditions and validity; whether the minutes were approved when it was issued; and the secretary's
 * certification with its fingerprint, to check the extract against memo. Never the vote, names or discussion.
 */
object ExtractPages {
    private val TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC)
    private val DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
    private val FACTORS = mapOf("TOTP" to "authenticator", "RECOVERY_CODE" to "recovery code")
    private const val HASH_SHOWN = 16

    /** The sentence on the minutes' approval the extract carries. */
    fun minutesLine(row: ExtractRow): String =
        row.minutesApprovedAt?.let { "The minutes of this meeting were approved on ${DAY.format(it.atZone(ZoneOffset.UTC))}." }
            ?: "The minutes of this meeting have not yet been approved."

    fun of(
        row: ExtractRow,
        c: ExtractContent,
        issuer: String,
        factors: List<String>,
    ): ReportDocument {
        val clean = MinutesTemplateData::clean
        val item =
            listOfNotNull(c.itemNumber?.let { "Item $it" }, c.itemTitle?.let(clean)).joinToString(". ").ifEmpty { "An item of the agenda" }
        val resolution =
            listOfNotNull(
                c.outcomeLabel.uppercase(Locale.ENGLISH),
                c.text?.takeIf { it.isNotBlank() }?.let(clean),
            ) + c.conditions.mapIndexed { i, cond -> "Condition ${i + 1}: ${clean(cond)}" } +
                listOfNotNull(c.validUntil?.let { "Valid until ${it.format(DAY)}." })
        val sections =
            listOf(
                ReportSection(
                    "The meeting",
                    lines =
                        listOf(
                            "Committee" to c.committee,
                            "Meeting" to c.meetingLabel,
                            "Date" to c.meetingDate.format(DAY),
                            "Item" to item,
                        ),
                ),
                ReportSection("Resolution ${c.number}", paragraphs = resolution),
                ReportSection("The minutes", paragraphs = listOf(minutesLine(row))),
                ReportSection(
                    "Certification",
                    paragraphs =
                        listOf(
                            "I certify that this is a true extract of resolution ${c.number} of the ${c.committee}, passed at its " +
                                "meeting ${c.meetingLabel} on ${c.meetingDate.format(DAY)}, as recorded in memo.",
                            "Before acting on this extract, check it in memo: open resolution ${c.number} and compare this " +
                                "extract's number and fingerprint with those listed there.",
                        ),
                    lines =
                        listOf(
                            "Certified by" to "$issuer, secretary of the committee",
                            "On" to TIME.format(row.issuedAt),
                            "Authenticated with" to
                                factors.joinToString(" and ") { FACTORS[it] ?: it.lowercase(Locale.ROOT) }.ifEmpty { "no recorded factor" },
                            "Extract" to row.id.toString(),
                            "Fingerprint" to row.certificationHash,
                        ),
                ),
            )
        return ReportDocument("Certified extract", "${c.committee} · ${c.meetingLabel}", "Certified extract · ${c.number}", sections)
    }

    /** The line at the foot of the page. */
    fun stamp(
        row: ExtractRow,
        c: ExtractContent,
        issuer: String,
    ): String =
        "Certified extract of resolution ${c.number} · certified by $issuer on ${TIME.format(row.issuedAt)} · " +
            "fingerprint ${row.certificationHash.take(HASH_SHOWN)}"

    fun metadata(
        row: ExtractRow,
        c: ExtractContent,
    ): RecordPdfMetadata =
        RecordPdfMetadata(
            title = "Certified extract of resolution ${c.number}",
            identifier = c.number,
            description = "Extract ${row.id}; certification ${row.certificationHash}",
            author = c.committee,
            created = row.issuedAt,
        )
}
