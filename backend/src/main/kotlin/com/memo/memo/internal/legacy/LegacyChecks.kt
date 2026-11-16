package com.memo.memo.internal.legacy

import com.memo.common.authz.Clearances
import com.memo.files.Files
import com.memo.identity.UserSummaries
import com.memo.memo.LegacyFileRole
import com.memo.memo.LegacyMemoRow
import com.memo.memo.LegacyNumbers
import com.memo.memo.LegacyPerson
import com.memo.memo.LegacyProblem
import com.memo.memo.LegacyStatus
import com.memo.memo.internal.MemoMetadata
import com.memo.memo.internal.MemoTypes
import com.memo.memo.internal.Memos
import com.memo.org.OrgUnitDirectory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate

/**
 * The memo module's rules for a legacy row (design E06 §9): lengths, the mapped type, level and unit, the type's fields
 * (without `requireAll`: required fields are a submit rule), dates, approvals, originals, linked people and the number.
 * The imports module adds its own (mappings, matching files, the batch). Reads only, inside a tenant transaction.
 */
@Component
class LegacyChecks(
    private val types: MemoTypes,
    private val metadata: MemoMetadata,
    private val clearances: Clearances,
    private val units: OrgUnitDirectory,
    private val users: UserSummaries,
    private val reader: LegacyMemoReader,
    private val files: Files,
    private val clock: Clock,
) {
    fun problems(row: LegacyMemoRow): List<LegacyProblem> =
        (text(row) + mapping(row) + dates(row) + approvals(row) + originals(row) + people(row)).distinct()

    /** For a create: every original is a stored `memo.legacy` file of this memo with a current version. */
    fun stored(row: LegacyMemoRow): List<LegacyProblem> =
        row.originals.mapIndexedNotNull { i, original ->
            val info = original.fileId?.let(files::info)
            LegacyProblem("FILE_NOT_RECEIVED", "originals/$i").takeIf {
                info == null || info.purpose != MemoLegacyPurpose.KEY || info.ownerId != row.memoId.toString() ||
                    info.currentVersionId == null
            }
        }

    private fun text(row: LegacyMemoRow): List<LegacyProblem> {
        val given = row.given
        val number = row.source.legacyNumber
        return listOfNotNull(
            problem("VALUE_INVALID", "source_code") { !SOURCE_CODE.matches(row.source.sourceCode) },
            problem("VALUE_INVALID", "row_number") { row.source.rowNumber < 1 },
            problem("VALUE_REQUIRED", "legacy_number") { number.isBlank() },
            problem("NUMBER_TOO_LONG", "legacy_number") { LegacyNumbers.key(number).length > LegacyNumbers.MAX_LENGTH },
            problem("NUMBER_TOO_LONG", "reference") {
                row.mapped.reference
                    .trim()
                    .length > LegacyNumbers.MAX_LENGTH
            },
            problem("VALUE_REQUIRED", "reference") { row.mapped.reference.isBlank() },
            problem("VALUE_REQUIRED", "subject") { given.subject.isBlank() },
            problem("SUBJECT_TOO_LONG", "subject") { given.subject.length > Memos.MAX_SUBJECT },
            length(given.status, "status", MAX_TEXT, required = true),
            length(given.type, "memo_type", MAX_TEXT, required = true),
            length(given.unit, "origin_unit", MAX_TEXT, required = true),
            length(given.classification, "classification", MAX_TEXT, required = false),
        ) + person(row.initiator, "initiator")
    }

    /** The mapped type (with a published version), level and unit exist; the field values suit the type. */
    private fun mapping(row: LegacyMemoRow): List<LegacyProblem> {
        val mapped = row.mapped
        val version = types.published(mapped.typeId)
        val fields =
            version
                ?.let { metadata.check(it.settings.fields, LegacyFieldText.typed(it.settings.fields, mapped.metadata), requireAll = false) }
                ?.problems
                .orEmpty()
                .map {
                    LegacyProblem("FIELD_INVALID", "field:${it.field.removePrefix("metadata.")}")
                }
        return listOfNotNull(
            problem("TYPE_UNMAPPED", "memo_type") { version == null },
            problem("CLASSIFICATION_UNMAPPED", "classification") { clearances.level(mapped.classificationId) == null },
            problem("UNIT_UNMAPPED", "origin_unit") { units.units(listOf(mapped.originUnitId)).isEmpty() },
            problem("NUMBER_IN_USE", "reference") { reader.referenceTaken(mapped.reference.trim()) },
        ) + fields
    }

    /** raised ≤ decided ≤ closed, none before 1900 or after today in the batch's zone; decisions and closings dated. */
    private fun dates(row: LegacyMemoRow): List<LegacyProblem> {
        val today = LocalDate.now(clock.withZone(row.source.timeZone))
        val (raised, decided, closed) = row.dates
        val status = row.mapped.status
        val all = listOfNotNull(raised, decided, closed) + row.approvals.mapNotNull { it.decidedOn }
        return listOfNotNull(
            problem("DATE_INVALID", "dates") { all.any { it.isBefore(EARLIEST) } },
            problem("DATE_FUTURE", "dates") { all.any { it.isAfter(today) } },
            problem("DATE_ORDER", "dates") {
                (decided != null && decided.isBefore(raised)) || (closed != null && closed.isBefore(decided ?: raised))
            },
            problem("VALUE_REQUIRED", "decided_on") {
                decided == null &&
                    (status == LegacyStatus.APPROVED || status == LegacyStatus.REJECTED)
            },
            problem("VALUE_REQUIRED", "closed_on") { closed == null && status == LegacyStatus.CLOSED },
        )
    }

    private fun approvals(row: LegacyMemoRow): List<LegacyProblem> {
        val ordinals = row.approvals.map { it.ordinal }
        return listOfNotNull(
            problem("TOO_MANY_APPROVALS", "approvals") { row.approvals.size > MAX_APPROVALS || ordinals.any { it > MAX_APPROVALS } },
            problem("VALUE_INVALID", "approvals") { ordinals.any { it < 1 } },
            problem("APPROVAL_ORDER_DUPLICATE", "approvals") { ordinals.size != ordinals.toSet().size },
        ) +
            row.approvals.flatMap { approval ->
                val at = "approvals/${approval.ordinal}"
                person(approval.person, at) +
                    listOfNotNull(
                        length(approval.decision, "$at/decision", MAX_DECISION, required = true),
                        length(approval.position, "$at/position", MAX_TEXT, required = false),
                        length(approval.remark, "$at/remark", MAX_REMARK, required = false),
                    )
            }
    }

    /** One MAIN, at most 51 originals, titles and paths in bounds, a file's own level strictly above the memo's. */
    private fun originals(row: LegacyMemoRow): List<LegacyProblem> {
        val mains = row.originals.count { it.role == LegacyFileRole.MAIN }
        val memoRank = clearances.level(row.mapped.classificationId)?.rank
        return listOfNotNull(
            problem("MAIN_FILE_MISSING", "originals") { mains == 0 },
            problem("MAIN_FILE_MULTIPLE", "originals") { mains > 1 },
            problem("TOO_MANY_FILES", "originals") { row.originals.size > MAX_ORIGINALS },
        ) +
            row.originals.flatMapIndexed { i, original ->
                val level = original.classificationId?.let(clearances::level)
                listOfNotNull(
                    length(original.title, "originals/$i/title", MAX_TITLE, required = true),
                    length(original.relativePath, "originals/$i/path", MAX_PATH, required = true),
                    problem(
                        "CLASSIFICATION_UNMAPPED",
                        "originals/$i/classification",
                    ) { original.classificationId != null && level == null },
                    problem("FILE_CLASSIFICATION_LOWER", "originals/$i/classification") {
                        level != null && memoRank != null && level.rank <= memoRank
                    },
                )
            }
    }

    /** A linked person must be a user of the tenant (any status: a leaver links too). */
    private fun people(row: LegacyMemoRow): List<LegacyProblem> {
        val linked = (listOf(row.initiator) + row.approvals.map { it.person }).mapNotNull { it.userId }.toSet()
        val known = users.of(linked).keys
        return linked.filterNot { it in known }.map { LegacyProblem("PERSON_UNKNOWN", "people/$it") }
    }

    private fun person(
        person: LegacyPerson,
        at: String,
    ): List<LegacyProblem> =
        listOfNotNull(
            length(person.name, "$at/name", MAX_TEXT, required = true),
            length(person.email, "$at/email", MAX_EMAIL, required = false),
            length(person.legacyId, "$at/legacy_id", MAX_LEGACY_ID, required = false),
        )

    private fun length(
        value: String?,
        field: String,
        max: Int,
        required: Boolean,
    ): LegacyProblem? =
        when {
            value.isNullOrBlank() -> if (required) LegacyProblem("VALUE_REQUIRED", field) else null
            value.length > max -> LegacyProblem("VALUE_TOO_LONG", field)
            else -> null
        }

    private fun problem(
        code: String,
        field: String,
        failed: () -> Boolean,
    ): LegacyProblem? = LegacyProblem(code, field).takeIf { failed() }

    internal companion object {
        val EARLIEST: LocalDate = LocalDate.of(1900, 1, 1)
        val SOURCE_CODE = Regex("^[A-Z0-9][A-Z0-9-]{0,19}$")
        const val MAX_TEXT = 200
        const val MAX_DECISION = 100
        const val MAX_REMARK = 4_000
        const val MAX_TITLE = 255
        const val MAX_PATH = 1_024
        const val MAX_EMAIL = 320
        const val MAX_LEGACY_ID = 100
        const val MAX_APPROVALS = 50

        /** One MAIN and 50 attachments (ADR-0046's limit). */
        const val MAX_ORIGINALS = 51
    }
}
