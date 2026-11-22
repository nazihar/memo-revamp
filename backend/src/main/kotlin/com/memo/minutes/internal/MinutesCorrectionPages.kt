package com.memo.minutes.internal

import com.memo.memo.ReportDocument
import com.memo.memo.ReportSection
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The corrections' pages of a corrected version of the approved minutes' record (E06 T8, design E06 §1.6.5), pure, in
 * English, times in UTC: what the version adds on its cover, and the "Corrections" annex.
 */
object MinutesCorrectionPages {
    private val TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC)
    private val FACTORS = mapOf("TOTP" to "authenticator", "RECOVERY_CODE" to "recovery code")

    /** A corrected version's cover: what this version adds, and the approval it records. */
    fun correctedVersion(c: RecordContext): ReportSection =
        ReportSection(
            "This version",
            paragraphs =
                listOf(
                    "The approved minutes are never edited. This version of the record keeps the approved document as it " +
                        "was and adds ${c.corrections.size} approved correction${if (c.corrections.size == 1) "" else "s"} in " +
                        "the annex \"Corrections\". The earlier versions stay locked and readable.",
                ),
            lines =
                listOf(
                    "Corrected on" to TIME.format(c.approval.at),
                    "This version's approval" to c.approval.recordHash,
                ),
        )

    /**
     * The corrections annex (E06 T8): each approved correction this version carries, oldest first, with who approved it,
     * when and how, and its approval's hash. A correction on an item [set]'s reader may not read in full shows its number
     * and date only.
     */
    fun corrections(
        c: RecordContext,
        set: RedactionSet,
    ): ReportDocument {
        val redacted = set.redacted.map { it.itemId }.toSet()
        val items = c.source.items.associateBy { it.id }
        val sections =
            c.corrections.mapIndexed { i, rc ->
                val item = rc.correction.itemId?.let(items::get)
                val title = "Correction ${i + 1}"
                val approved =
                    "Approved on ${TIME.format(rc.approval.at)} by ${rc.approver} (${MinutesRecordPages.role(rc.approval.role)}), " +
                        "authenticated with " +
                        "${factors(rc.approval)}; approval ${rc.approval.recordHash}."
                if (rc.correction.itemId != null && rc.correction.itemId in redacted) {
                    ReportSection(
                        title,
                        paragraphs =
                            listOf("A correction to item ${item?.number.orEmpty()} was approved; it is not shown in this copy.", approved),
                    )
                } else {
                    val about =
                        item?.let { "Item ${it.number.orEmpty()}. ${MinutesTemplateData.clean(it.title)}" } ?: "The minutes in general"
                    ReportSection(
                        title,
                        paragraphs =
                            listOf(
                                about,
                                MinutesTemplateData.clean(rc.correction.text),
                                "Reason: ${MinutesTemplateData.clean(rc.correction.reason)}",
                                approved,
                            ),
                    )
                }
            }
        return MinutesRecordPages.document(c, "Corrections", sections)
    }

    fun factors(a: ApprovalRow): String {
        val factors = a.authentication["factors"]?.let { f -> (0 until f.size()).map { f.get(it).asString() } }.orEmpty()
        return factors.joinToString(" and ") { FACTORS[it] ?: it.lowercase(Locale.ROOT) }.ifEmpty { "no recorded factor" }
    }
}
