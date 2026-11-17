package com.memo.memo.internal

import com.memo.audit.AuditLog
import com.memo.common.web.FieldProblem
import com.memo.common.web.RefusedException
import com.memo.org.OrgDirectory
import com.memo.org.OrgUnitDirectory
import org.jooq.exception.IntegrityConstraintViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Numbers memos (ADR-0044) as their pinned type version says: at its first submit or at its final approval, in that
 * transaction, from the counter of its series, period (the date in the tenant's time zone) and scope. A memo that already
 * has a number (a Phase 2 reference, or a resubmission) keeps it. Inside a tenant transaction.
 */
@Component
class MemoNumbers(
    private val numbers: MemoNumberStore,
    private val units: OrgUnitDirectory,
    private val directory: OrgDirectory,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    /** What stops [memo] from being numbered by [version] later: a number with a unit needs the memo's origin unit. */
    fun problems(
        memo: Memo,
        version: MemoTypeVersionRow,
    ): List<FieldProblem> =
        listOfNotNull(
            FieldProblem("originUnit", "NO_ORIGIN_UNIT", "Memos of this type are numbered by unit, and you hold no position in a unit.")
                .takeIf { memo.reference == null && version.settings.numbering.needsUnit && memo.originUnitId == null },
        )

    /** Numbers [memo] if [version] numbers at [moment] and it has no number yet; returns its number either way. */
    fun assign(
        memo: Memo,
        version: MemoTypeVersionRow,
        moment: NumberMoment,
    ): String? {
        val numbering = version.settings.numbering
        if (memo.reference != null || numbering.moment != moment) return memo.reference
        val today = directory.today()
        val unit = memo.originUnitId?.let { units.units(listOf(it))[it] }
        check(!numbering.needsUnit || unit != null) { "memo ${memo.id} is numbered by unit and has none" }
        val key =
            NumberKey(
                numbering.series,
                NumberFormats.period(numbering, today),
                if (numbering.countPer == NumberCountPer.UNIT) checkNotNull(unit).id.toString() else "",
            )
        // Taken late in the transaction: the counter row stays locked from here until commit.
        val sequence = numbers.next(key)
        val reference = NumberFormats.render(numbering, version.settings.code, unit?.code, today, sequence)
        check(reference.length <= NumberFormats.MAX_LENGTH) { "number longer than ${NumberFormats.MAX_LENGTH}" }
        val written =
            try {
                numbers.setNumber(memo.id, reference, key, sequence, now())
            } catch (_: DuplicateKeyException) {
                false
            } catch (_: IntegrityConstraintViolationException) {
                false
            }
        if (!written) throw RefusedException("NUMBER_TAKEN", "Another memo already has the number $reference; ask an administrator.")
        audit.record(
            "memo.numbered",
            Memos.OBJECT_TYPE,
            memo.id.toString(),
            after =
                mapOf(
                    "reference" to reference,
                    "series" to key.series,
                    "period" to key.period,
                    "scope" to key.scope,
                    "sequence" to sequence,
                ),
        )
        return reference
    }

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
}
