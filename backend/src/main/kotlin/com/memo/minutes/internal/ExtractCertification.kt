package com.memo.minutes.internal

import org.springframework.stereotype.Component
import java.time.temporal.ChronoUnit

/**
 * The fingerprint of a certified resolution extract (design E06 §1.6.6): the SHA-256 of what it certifies, and the
 * certification's hash over who certified it, when, how they were authenticated (ADR-0051) and the minutes' state at
 * issue. The issue records it; the job makes the extract only while the resolution still says what was certified.
 */
@Component
class ExtractCertification(
    private val evidence: ApprovalEvidence,
) {
    /** SHA-256 over what [content] says: the extract certifies this, and the job refuses to print anything else. */
    fun contentSha256(content: ExtractContent): String =
        evidence.hash(
            mapOf(
                "resolutionId" to content.resolutionId.toString(),
                "number" to content.number,
                "committee" to content.committee,
                "meeting" to content.meetingLabel,
                "date" to content.meetingDate.toString(),
                "itemNumber" to content.itemNumber,
                "itemTitle" to content.itemTitle,
                "outcome" to content.outcomeLabel,
                "text" to content.text,
                "conditions" to content.conditions,
                "validUntil" to content.validUntil?.toString(),
            ),
        )

    /** The certification's hash: who certified what, when, how authenticated, and the minutes' state at issue. */
    fun certificationHash(
        row: ExtractRow,
        contentSha256: String,
    ): String =
        evidence.hash(
            mapOf(
                "id" to row.id.toString(),
                "resolutionId" to row.resolutionId.toString(),
                "issuedBy" to row.issuedBy.toString(),
                "issuedAt" to row.issuedAt.truncatedTo(ChronoUnit.MICROS).toString(),
                "minutesState" to row.minutesState.name,
                "minutesApprovedAt" to row.minutesApprovedAt?.truncatedTo(ChronoUnit.MICROS)?.toString(),
                "certification" to evidence.mapOf(row.certification),
                "contentSha256" to contentSha256,
            ),
        )
}
