package com.memo.memo.internal.legacy

import com.memo.audit.AuditLog
import com.memo.common.authz.Clearances
import com.memo.common.authz.Policy
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.memo.internal.Memo
import com.memo.memo.internal.MemoChangeLog
import com.memo.memo.internal.MemoRejectedException
import com.memo.memo.internal.MemoStatus
import com.memo.memo.internal.MemoStore
import com.memo.memo.internal.Memos
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * What a correction after import changes (design E06 §11): a legacy fact, the memo's subject, or (through reclassify only)
 * its classification. Stored by name in `memo_legacy_correction.field`.
 */
enum class LegacyCorrectionField { SUBJECT, RAISED_ON, DECIDED_ON, CLOSED_ON, INITIATOR_NAME, LEGACY_UNIT, LEGACY_TYPE, CLASSIFICATION }

/** An imported memo's legacy facts as they stand: as given, with each field's latest correction applied. */
data class LegacyCurrent(
    val raisedOn: LocalDate,
    val decidedOn: LocalDate?,
    val closedOn: LocalDate?,
    val initiatorName: String,
    val unit: String,
    val type: String,
) {
    /** [field] as stored text (dates as `yyyy-mm-dd`); null for the fields kept on the memo row and for a missing date. */
    fun text(field: LegacyCorrectionField): String? =
        when (field) {
            LegacyCorrectionField.RAISED_ON -> raisedOn.toString()
            LegacyCorrectionField.DECIDED_ON -> decidedOn?.toString()
            LegacyCorrectionField.CLOSED_ON -> closedOn?.toString()
            LegacyCorrectionField.INITIATOR_NAME -> initiatorName
            LegacyCorrectionField.LEGACY_UNIT -> unit
            LegacyCorrectionField.LEGACY_TYPE -> type
            LegacyCorrectionField.SUBJECT, LegacyCorrectionField.CLASSIFICATION -> null
        }

    companion object {
        /** [facts] with [corrections] (oldest first) applied: the last correction of a field wins, also when it clears it. */
        fun of(
            facts: LegacyFacts,
            corrections: List<LegacyCorrectionRow>,
        ): LegacyCurrent {
            fun latest(
                field: LegacyCorrectionField,
                given: String?,
            ): String? = corrections.lastOrNull { it.field == field.name }.let { if (it == null) given else it.after }

            fun date(
                field: LegacyCorrectionField,
                given: LocalDate?,
            ): LocalDate? = latest(field, given?.toString())?.let(LocalDate::parse)
            return LegacyCurrent(
                raisedOn = date(LegacyCorrectionField.RAISED_ON, facts.raisedOn) ?: facts.raisedOn,
                decidedOn = date(LegacyCorrectionField.DECIDED_ON, facts.decidedOn),
                closedOn = date(LegacyCorrectionField.CLOSED_ON, facts.closedOn),
                initiatorName = latest(LegacyCorrectionField.INITIATOR_NAME, facts.initiator.name) ?: facts.initiator.name,
                unit = latest(LegacyCorrectionField.LEGACY_UNIT, facts.unit) ?: facts.unit,
                type = latest(LegacyCorrectionField.LEGACY_TYPE, facts.type) ?: facts.type,
            )
        }
    }
}

/**
 * Corrections and classification raises of imported memos (ADR-0055, design E06 §10, §11). The legacy facts stay as
 * given: each change is a new `memo_legacy_correction` row with the value before, the value after and a reason, audited
 * in the same transaction. Only the subject and the classification live on the memo row, and only they change there
 * (the `memo_imported_guard` allows nothing else, and the level only upwards). Search follows (`MemoChanged`). The caller
 * holds `memo.import` and has stepped up; the memo must be readable by them (else 404). Inside a tenant transaction.
 */
@Service
class LegacyCorrections(
    private val memos: Memos,
    private val store: MemoStore,
    private val reader: LegacyMemoReader,
    private val writer: LegacyMemoWriter,
    private val clearances: Clearances,
    private val policy: Policy,
    private val changes: MemoChangeLog,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    /** Corrects [field] of imported memo [memoId] to [value] (blank: none, for a decision date the status allows without). */
    fun correct(
        memoId: UUID,
        field: LegacyCorrectionField,
        value: String?,
        reason: String,
    ): Memo {
        val memo = imported(memoId)
        val facts = checkNotNull(reader.facts(memo.id)) { "imported memo ${memo.id} has legacy facts" }
        val current = LegacyCurrent.of(facts, reader.corrections(memo.id))
        val after = value?.trim()?.takeIf { it.isNotEmpty() }
        val before = if (field == LegacyCorrectionField.SUBJECT) memo.subject else current.text(field)
        val today = LocalDate.now(clock.withZone(ZoneId.of(facts.timeZone)))
        val problems =
            listOfNotNull(
                LegacyCorrectionRules.value(field, after, before) ?: LegacyCorrectionRules.dates(field, after, current, memo.status, today),
                LegacyCorrectionRules.reason(reason),
            )
        if (problems.isNotEmpty()) throw MemoRejectedException(problems)
        val now = now()
        writer.correction(LegacyCorrection(memo.id, field, before, after, reason.trim(), user()), now)
        revise(memo, if (field == LegacyCorrectionField.SUBJECT) checkNotNull(after) else memo.subject, null, now)
        audit.record(
            "memo.legacy.corrected",
            Memos.OBJECT_TYPE,
            memo.id.toString(),
            before = mapOf("field" to field.name, "value" to before),
            after = mapOf("field" to field.name, "value" to after, "reason" to reason.trim()),
        )
        return memos.get(memo.id)
    }

    /**
     * Raises imported memo [memoId] to the level [classificationId] (never lowers it); the reader must be cleared to it.
     * Its originals' effective level follows (the higher of theirs and the memo's), and so do its search rows.
     */
    fun reclassify(
        memoId: UUID,
        classificationId: UUID,
        reason: String,
    ): Memo {
        val memo = imported(memoId)
        val level = clearances.level(classificationId)
        val problems =
            listOfNotNull(
                when {
                    level == null -> {
                        FieldProblem("classificationId", "unknown", "No such classification level.")
                    }

                    level.rank <= memo.classificationRank -> {
                        FieldProblem("classificationId", "CLASSIFICATION_NOT_HIGHER", "Choose a level above the memo's.")
                    }

                    level.rank > policy.clearanceRank() -> {
                        FieldProblem("classificationId", "above-clearance", "You are not cleared for this level.")
                    }

                    else -> {
                        null
                    }
                },
                LegacyCorrectionRules.reason(reason),
            )
        if (problems.isNotEmpty()) throw MemoRejectedException(problems)
        val raised = checkNotNull(level)
        val was = clearances.level(memo.classificationId)
        val now = now()
        val correction =
            LegacyCorrection(memo.id, LegacyCorrectionField.CLASSIFICATION, was?.name, raised.name, reason.trim(), user())
        writer.correction(correction, now)
        revise(memo, memo.subject, raised.id to raised.rank, now)
        audit.record(
            "memo.legacy.reclassified",
            Memos.OBJECT_TYPE,
            memo.id.toString(),
            before = mapOf("classificationId" to memo.classificationId.toString(), "rank" to memo.classificationRank),
            after = mapOf("classificationId" to raised.id.toString(), "rank" to raised.rank, "reason" to reason.trim()),
        )
        return memos.get(memo.id)
    }

    /** The memo if the actor may read it (else 404) and it was imported (else 409 `NOT_IMPORTED`), locked until commit. */
    private fun imported(memoId: UUID): Memo {
        memos.get(memoId)
        val memo = checkNotNull(store.lock(memoId))
        refuseIf(!memo.imported, "NOT_IMPORTED") { "Only an imported memo has legacy facts to correct." }
        return memo
    }

    /** The memo row: a new version and update time, the subject, and a raised level when given. Search follows. */
    private fun revise(
        memo: Memo,
        subject: String,
        level: Pair<UUID, Int>?,
        now: OffsetDateTime,
    ) {
        val revised = memo.copy(subject = subject, classificationId = level?.first ?: memo.classificationId)
        check(writer.revise(revised, level?.second ?: memo.classificationRank, now)) { "memo ${memo.id} is locked" }
        changes.memo(memo.id)
    }

    private fun user(): UUID = checkNotNull((ActorContext.current().actor as? Actor.User)?.userId) { "corrections need a signed-in user" }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
}

/** A correction to append: the field, its value before and after, why, and who made it. */
data class LegacyCorrection(
    val memoId: UUID,
    val field: LegacyCorrectionField,
    val before: String?,
    val after: String?,
    val reason: String,
    val by: UUID,
)

/** The rules of a correction (design E06 §11): lengths as at import, dates as the dry run checks them, and a reason. */
internal object LegacyCorrectionRules {
    const val MAX_REASON = 500
    private val dateFields =
        setOf(LegacyCorrectionField.RAISED_ON, LegacyCorrectionField.DECIDED_ON, LegacyCorrectionField.CLOSED_ON)

    fun reason(reason: String): FieldProblem? =
        FieldProblem("reason", "length", "Give a reason of 1 to $MAX_REASON characters.")
            .takeIf { reason.trim().length !in 1..MAX_REASON }

    /** A problem with [after] as text: the field is not corrected here, nothing changes, or a length; dates come next. */
    fun value(
        field: LegacyCorrectionField,
        after: String?,
        before: String?,
    ): FieldProblem? {
        val max = if (field == LegacyCorrectionField.SUBJECT) Memos.MAX_SUBJECT else LegacyChecks.MAX_TEXT
        return when {
            field == LegacyCorrectionField.CLASSIFICATION -> FieldProblem("field", "USE_RECLASSIFY", "Raise the level with reclassify.")
            after == before -> FieldProblem("value", "UNCHANGED", "The value is already this.")
            field in dateFields -> null
            after == null || after.length > max -> FieldProblem("value", "length", "Enter 1 to $max characters.")
            else -> null
        }
    }

    /**
     * A problem with the dates once [field] is [after]: not a date, before 1900 or after [today], out of order (raised, then
     * decided, then closed), or missing where the memo's status needs it (decided for APPROVED and REJECTED, closed for
     * CLOSED, raised always). Null for a field that is not a date.
     */
    fun dates(
        field: LegacyCorrectionField,
        after: String?,
        current: LegacyCurrent,
        status: MemoStatus,
        today: LocalDate,
    ): FieldProblem? {
        val date = after?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val raised = if (field == LegacyCorrectionField.RAISED_ON) date else current.raisedOn
        val decided = if (field == LegacyCorrectionField.DECIDED_ON) date else current.decidedOn
        val closed = if (field == LegacyCorrectionField.CLOSED_ON) date else current.closedOn
        val all = listOfNotNull(raised, decided, closed)
        return when {
            field !in dateFields -> null
            after != null && date == null -> FieldProblem("value", "DATE_INVALID", "Enter a date as yyyy-mm-dd.")
            all.any { it.isBefore(LegacyChecks.EARLIEST) } -> FieldProblem("value", "DATE_INVALID", "Dates start in 1900.")
            all.any { it.isAfter(today) } -> FieldProblem("value", "DATE_FUTURE", "The date is in the future.")
            missing(raised, decided, closed, status) -> FieldProblem("value", "VALUE_REQUIRED", "This memo's status needs this date.")
            outOfOrder(raised, decided, closed) -> FieldProblem("value", "DATE_ORDER", "Raised, decided and closed must follow each other.")
            else -> null
        }
    }

    private fun missing(
        raised: LocalDate?,
        decided: LocalDate?,
        closed: LocalDate?,
        status: MemoStatus,
    ): Boolean =
        raised == null ||
            (decided == null && (status == MemoStatus.APPROVED || status == MemoStatus.REJECTED)) ||
            (closed == null && status == MemoStatus.CLOSED)

    private fun outOfOrder(
        raised: LocalDate?,
        decided: LocalDate?,
        closed: LocalDate?,
    ): Boolean =
        (raised != null && decided != null && decided.isBefore(raised)) ||
            (closed != null && closed.isBefore(decided ?: raised ?: closed))
}
