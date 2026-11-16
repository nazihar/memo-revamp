package com.memo.imports.internal.dryrun

import com.memo.common.authz.ClassificationLevelRef
import com.memo.imports.internal.ImportBatch
import com.memo.imports.internal.ImportMapping
import com.memo.imports.internal.MappingKind
import com.memo.imports.internal.StatusTarget
import com.memo.imports.internal.sheet.SheetColumns
import com.memo.memo.LegacyAsGiven
import com.memo.memo.LegacyDates
import com.memo.memo.LegacyMapped
import com.memo.memo.LegacyMemoRow
import com.memo.memo.LegacyNumbers
import com.memo.memo.LegacySource
import com.memo.memo.LegacyStatus
import com.memo.memo.LiveSeries
import java.time.LocalDate
import java.util.UUID

/** What the validation reads once per dry run: the batch, its mappings, the tenant's levels and series, and cross-row facts. */
class ValidationContext(
    val batch: ImportBatch,
    mappings: List<ImportMapping>,
    val levels: List<ClassificationLevelRef>,
    val series: List<LiveSeries>,
    val duplicateKeys: Set<String>,
    val similarKeys: Set<String>,
    val sharedRows: Set<UUID>,
    val findings: StageFindings,
) {
    private val byKind = mappings.groupBy { it.kind }.mapValues { (_, list) -> list.associateBy { it.legacyValue } }
    private val levelsById = levels.associateBy { it.id }

    fun mapping(
        kind: MappingKind,
        value: String,
    ): ImportMapping? = byKind[kind]?.get(value)

    fun rank(levelId: UUID?): Int? = levelId?.let { levelsById[it]?.rank }
}

/**
 * A Memos row assembled for `LegacyMemos.check` (design E06 §5.2 step 4, §6): its values mapped through the batch's
 * mappings, its Files rows with the originals they matched, its Approvals rows, and the imports module's own findings
 * (mappings missing or unconfirmed, values that do not parse, files not received, the live series, cross-row facts).
 * [originRows] and [approvalRows] tell where a check's problem about an original or an approval sits in the sheets.
 */
data class AssembledRow(
    val row: LegacyMemoRow,
    val findings: List<Finding>,
    val rank: Int?,
    val originRows: List<Int>,
    val approvalRows: Map<Int, Int>,
)

/** Builds [AssembledRow]s; pure apart from the [ValidationContext] it reads. */
class RowAssembly(
    private val ctx: ValidationContext,
) {
    private val lookup = MappingLookup(ctx)

    fun assemble(
        memo: StoredMemo,
        files: List<StoredFile>,
        approvals: List<StoredApproval>,
    ): AssembledRow = Builder(memo, files.sortedBy { it.staged.rowNumber }, approvals.sortedBy { it.staged.rowNumber }).build()

    private inner class Builder(
        private val memo: StoredMemo,
        private val files: List<StoredFile>,
        private val approvals: List<StoredApproval>,
    ) {
        private val findings = RowFindings(memo.rowNumber)
        private val cells = memo.cells
        private val people = PeoplePart(ctx, findings)

        fun build(): AssembledRow {
            findings.all += ctx.findings.of(ProblemSheet.MEMOS, memo.rowNumber)
            files.forEach { findings.all += ctx.findings.of(ProblemSheet.FILES, it.staged.rowNumber) }
            approvals.forEach { findings.all += ctx.findings.of(ProblemSheet.APPROVALS, it.staged.rowNumber) }
            val number = cells[MemoColumns.LEGACY_NUMBER].orEmpty()
            val reference = (ctx.batch.settings.numberPrefix ?: "") + number.trim()
            number(number, reference)
            val classification = memoLevel()
            val originals = OriginalsPart(lookup, findings).of(files)
            val legacyApprovals = people.approvals(approvals)
            val initiator =
                people.person(
                    cells[MemoColumns.INITIATOR_NAME],
                    cells[MemoColumns.INITIATOR_EMAIL],
                    cells[MemoColumns.INITIATOR_ID],
                    PersonPlace(
                        ProblemSheet.MEMOS,
                        memo.rowNumber,
                        MemoColumns.INITIATOR_NAME,
                        MemoColumns.INITIATOR_EMAIL,
                        MemoColumns.INITIATOR_ID,
                    ),
                )
            val row =
                LegacyMemoRow(
                    memoId = memo.id,
                    source = source(number),
                    given = given(),
                    mapped = mapped(classification, reference),
                    dates = dates(),
                    initiator = initiator,
                    approvals = legacyApprovals.map { it.second },
                    originals = originals.map { it.first },
                )
            if (memo.id in ctx.sharedRows) findings.memo(ProblemLevel.WARNING, "FILE_SHARED", null)
            val ranks = listOfNotNull(ctx.rank(classification)) + originals.mapNotNull { ctx.rank(it.first.classificationId) }
            return AssembledRow(
                row = row,
                findings = findings.all,
                rank = ranks.maxOrNull().takeIf { classification != null },
                originRows = originals.map { it.second },
                approvalRows = legacyApprovals.associate { it.second.ordinal to it.first },
            )
        }

        /** Required, unique in the batch, flagged when similar to another, and not one a live series may still give. */
        private fun number(
            number: String,
            reference: String,
        ) {
            val key = number.takeIf { it.isNotBlank() }?.let(LegacyNumbers::key)
            when {
                key == null -> findings.error("VALUE_REQUIRED", MemoColumns.LEGACY_NUMBER)
                key in ctx.duplicateKeys -> findings.error("NUMBER_DUPLICATE_IN_BATCH", MemoColumns.LEGACY_NUMBER)
                else -> Unit
            }
            if (key != null && key in ctx.similarKeys) findings.memo(ProblemLevel.WARNING, "NUMBER_SIMILAR", MemoColumns.LEGACY_NUMBER)
            val live = ctx.series.firstOrNull { reference.isNotBlank() && it.mayStillGive(reference) }
            if (live != null) findings.error("NUMBER_IN_LIVE_SERIES", MemoColumns.LEGACY_NUMBER, mapOf("format" to live.format))
        }

        private fun source(number: String) =
            LegacySource(
                batchId = ctx.batch.id,
                rowNumber = memo.rowNumber,
                sourceCode = ctx.batch.settings.sourceCode,
                legacyNumber = number,
                fingerprint = RowFingerprint.of(cells, approvals, files),
                timeZone = ctx.batch.settings.timeZone,
                startedBy = ctx.batch.createdBy,
            )

        private fun given() =
            LegacyAsGiven(
                status = cells[MemoColumns.STATUS].orEmpty(),
                type = cells[MemoColumns.MEMO_TYPE].orEmpty(),
                unit = cells[MemoColumns.ORIGIN_UNIT].orEmpty(),
                subject = cells[MemoColumns.SUBJECT].orEmpty(),
                classification = cells[MemoColumns.CLASSIFICATION],
            )

        /** Unmapped values stand in as ids no tenant has, so the check adds nothing about what is already reported. */
        private fun mapped(
            classification: UUID?,
            reference: String,
        ) = LegacyMapped(
            typeId = target(MappingKind.TYPE, MemoColumns.MEMO_TYPE, "TYPE_UNMAPPED")?.let(::uuid) ?: PLACEHOLDER,
            status = status(),
            classificationId = classification ?: PLACEHOLDER,
            originUnitId = target(MappingKind.UNIT, MemoColumns.ORIGIN_UNIT, "UNIT_UNMAPPED")?.let(::uuid) ?: PLACEHOLDER,
            reference = reference,
            metadata = cells.filterKeys { SheetColumns.isField(it) }.mapKeys { it.key.removePrefix(SheetColumns.FIELD_PREFIX) },
        )

        /** The mapped status; an unmapped one stands in as WITHDRAWN, which needs no dates (its own finding is recorded). */
        private fun status(): LegacyStatus {
            val target = target(MappingKind.STATUS, MemoColumns.STATUS, "STATUS_UNMAPPED")
            if (target == StatusTarget.NOT_IMPORTED.name) findings.error("IN_FLIGHT_NOT_IMPORTED", MemoColumns.STATUS)
            return LegacyStatus.entries.firstOrNull { it.name == target } ?: LegacyStatus.WITHDRAWN
        }

        /** The memo's level: mapped, or the batch's default for a blank (`CLASSIFICATION_DEFAULTED`), else none. */
        private fun memoLevel(): UUID? {
            val value = cells[MemoColumns.CLASSIFICATION]
            val default = ctx.batch.settings.defaultClassificationId
            return when {
                value != null -> {
                    target(MappingKind.CLASSIFICATION, MemoColumns.CLASSIFICATION, "CLASSIFICATION_UNMAPPED")?.let(::uuid)
                }

                default != null -> {
                    findings.memo(ProblemLevel.WARNING, "CLASSIFICATION_DEFAULTED", MemoColumns.CLASSIFICATION)
                    default
                }

                else -> {
                    findings.error("CLASSIFICATION_BLANK", MemoColumns.CLASSIFICATION)
                    null
                }
            }
        }

        private fun target(
            kind: MappingKind,
            column: String,
            unmapped: String,
        ): String? =
            cells[column]?.let { value ->
                lookup.resolve(kind, value, unmapped) { code, params -> findings.error(code, column, params) }
            }

        private fun dates(): LegacyDates {
            val raised = date(MemoColumns.RAISED_ON, required = true)
            val decided = date(MemoColumns.DECIDED_ON, required = false)
            val closed = date(MemoColumns.CLOSED_ON, required = false)
            // A raised-on date that is missing or unreadable is reported; the earliest other date stands in for the check.
            return LegacyDates(raised ?: listOfNotNull(decided, closed).minOrNull() ?: EARLIEST, decided, closed)
        }

        private fun date(
            column: String,
            required: Boolean,
        ): LocalDate? {
            val text = cells[column]
            val date = text?.let(::parseDate)
            when {
                text == null && required -> findings.error("VALUE_REQUIRED", column)
                text != null && date == null -> findings.error("DATE_INVALID", column)
                else -> Unit
            }
            return date
        }
    }

    companion object {
        /** A level, type or unit no tenant has: an unmapped value stands in as it. */
        val PLACEHOLDER: UUID = UUID(0, 0)
        private val EARLIEST: LocalDate = LocalDate.of(1900, 1, 1)

        fun parseDate(text: String): LocalDate? = LegacyDateText.parse(text)

        fun uuid(text: String): UUID? = runCatching { UUID.fromString(text) }.getOrNull()
    }
}
