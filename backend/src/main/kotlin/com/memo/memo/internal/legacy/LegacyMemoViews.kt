package com.memo.memo.internal.legacy

import com.memo.common.authz.Clearances
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.files.Files
import com.memo.identity.UserSummaries
import com.memo.memo.LegacyFileRole
import com.memo.memo.internal.Memo
import com.memo.memo.internal.Named
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A person as the legacy data names them, with the user they were linked to here, if any. */
data class LegacyPersonView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    val user: Named? = null,
)

/** A legacy decision, as given: history, not an approval authenticated by this system. */
data class LegacyApprovalView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val ordinal: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val person: LegacyPersonView,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val decision: String,
    val position: String? = null,
    val decidedOn: LocalDate? = null,
    val remark: String? = null,
)

/**
 * An original document of an imported memo. One above the reader's clearance is restricted: no title, type, size or file.
 * [sha256] is shown to holders of `audit.read`.
 */
data class LegacyOriginalView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val role: LegacyFileRole,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val position: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val restricted: Boolean,
    val fileId: UUID? = null,
    val versionId: UUID? = null,
    val title: String? = null,
    val mediaType: String? = null,
    val sizeBytes: Long? = null,
    /** Set when the original is stricter than the memo. */
    val classification: Named? = null,
    val sha256: String? = null,
)

/** A correction appended after import, with its reason. */
data class LegacyCorrectionView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val field: LegacyCorrectionField,
    /** As stored: text, a date as `yyyy-mm-dd`, or a level's name (CLASSIFICATION); null for a date that was or became empty. */
    val before: String? = null,
    val after: String? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val reason: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val correctedBy: Named,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val correctedAt: Instant,
)

/**
 * What the legacy system said about an imported memo (ADR-0055, design E06 §11), as given and never verified by this
 * system: where it came from, its dates as days in the batch's time zone, its values before mapping, its initiator, the
 * legacy approval history, its originals (the record) and the corrections appended since.
 */
data class LegacyMemoView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val sourceCode: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val batchId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val rowNumber: Int,
    /** The legacy number as given (the memo's reference may carry the batch's prefix). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val legacyNumber: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val importedAt: Instant,
    /** The IANA time zone the legacy dates are days in. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val timeZone: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val raisedOn: LocalDate,
    val decidedOn: LocalDate? = null,
    val closedOn: LocalDate? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val status: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val type: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val unit: String,
    val classification: String? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val subject: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val initiator: LegacyPersonView,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val approvals: List<LegacyApprovalView>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val originals: List<LegacyOriginalView>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val corrections: List<LegacyCorrectionView>,
)

/** Builds [LegacyMemoView]s for the memo view. Inside a tenant transaction; the caller has applied the read rule. */
@Component
class LegacyMemoViews(
    private val reader: LegacyMemoReader,
    private val users: UserSummaries,
    private val files: Files,
    private val clearances: Clearances,
    private val policy: Policy,
) {
    /** The legacy part of [memo]; null for a memo raised here. */
    fun of(memo: Memo): LegacyMemoView? {
        val facts = (if (memo.imported) reader.facts(memo.id) else null) ?: return null
        val approvals = reader.approvals(memo.id)
        val corrections = reader.corrections(memo.id)
        val names =
            users.names(
                listOfNotNull(facts.initiator.userId) + approvals.mapNotNull { it.person.userId } + corrections.map { it.correctedBy },
            )

        fun person(
            name: String,
            user: UUID?,
        ) = LegacyPersonView(name, user?.let { Named(it, names[it].orEmpty()) })
        return LegacyMemoView(
            sourceCode = facts.sourceCode,
            batchId = facts.batchId,
            rowNumber = facts.rowNumber,
            legacyNumber = facts.legacyNumber,
            importedAt = facts.importedAt,
            timeZone = facts.timeZone,
            raisedOn = facts.raisedOn,
            decidedOn = facts.decidedOn,
            closedOn = facts.closedOn,
            status = facts.status,
            type = facts.type,
            unit = facts.unit,
            classification = facts.classification,
            subject = facts.subject,
            initiator = person(facts.initiator.name, facts.initiator.userId),
            approvals =
                approvals.map {
                    LegacyApprovalView(
                        it.ordinal,
                        person(it.person.name, it.person.userId),
                        it.decision,
                        it.position,
                        it.decidedOn,
                        it.remark,
                    )
                },
            originals = originals(memo),
            corrections =
                corrections.map {
                    LegacyCorrectionView(
                        LegacyCorrectionField.valueOf(it.field),
                        it.before,
                        it.after,
                        it.reason,
                        Named(it.correctedBy, names[it.correctedBy].orEmpty()),
                        it.correctedAt,
                    )
                },
        )
    }

    /** The originals as the reader may see them: above their clearance, restricted. */
    private fun originals(memo: Memo): List<LegacyOriginalView> {
        val rows = reader.originals(memo.id)
        val clearance = policy.clearanceRank()
        val auditor = policy.allows(Permission.AUDIT_READ)
        val current = files.currentVersions(rows.map { it.fileId })
        return rows.map { row ->
            if (row.effectiveRank(memo.classificationRank) > clearance) {
                LegacyOriginalView(row.id, row.role, row.position, restricted = true)
            } else {
                val version = current[row.fileId]
                LegacyOriginalView(
                    id = row.id,
                    role = row.role,
                    position = row.position,
                    restricted = false,
                    fileId = row.fileId,
                    versionId = version?.id,
                    title = row.title,
                    mediaType = version?.mediaType,
                    sizeBytes = row.sizeBytes,
                    classification = row.classificationId?.let { id -> Named(id, clearances.level(id)?.name.orEmpty()) },
                    sha256 = row.sha256.takeIf { auditor },
                )
            }
        }
    }
}
