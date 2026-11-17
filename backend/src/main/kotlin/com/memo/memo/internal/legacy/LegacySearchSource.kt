package com.memo.memo.internal.legacy

import com.memo.files.Files
import com.memo.memo.LegacyFileRole
import com.memo.memo.MemoFileFacts
import com.memo.memo.internal.Memo
import com.memo.memo.internal.MemoStore
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * What the search index adds for an imported memo (design E06 §11): [raisedAt], the start of its legacy raised-on day in
 * the batch's time zone (as corrected), which the date filter uses; and [words], its legacy number, initiator, approvers,
 * unit and type as given and as corrected, so a memo is found by the name of someone who has left.
 */
data class LegacySearchWords(
    val raisedAt: Instant,
    val words: List<String>,
)

/** The search index's side of imported memos: their legacy words and their originals. Inside a tenant transaction. */
@Component
class LegacySearchSource(
    private val reader: LegacyMemoReader,
    private val memos: MemoStore,
    private val files: Files,
) {
    /** The legacy words of [memoId]; null when it has no legacy facts (a memo raised here). */
    fun words(memoId: UUID): LegacySearchWords? =
        reader.facts(memoId)?.let { facts ->
            val current = LegacyCurrent.of(facts, reader.corrections(memoId))
            val people = listOf(facts.initiator.name, current.initiatorName) + reader.approvals(memoId).map { it.person.name }
            val words = listOf(facts.legacyNumber) + people + listOf(facts.unit, current.unit, facts.type, current.type)
            LegacySearchWords(
                raisedAt = current.raisedOn.atStartOfDay(ZoneId.of(facts.timeZone)).toInstant(),
                words = words.map(String::trim).filter(String::isNotEmpty).distinct(),
            )
        }

    /** [fileId] as an original of an imported memo; null when it is not one. */
    fun file(fileId: UUID): MemoFileFacts? =
        reader.original(fileId)?.let { row ->
            memos.find(row.memoId)?.let { memo -> facts(row, memo.classificationRank, files.info(fileId)?.currentVersionId) }
        }

    /** The originals of [memo] when it was imported; none for a memo raised here. */
    fun files(memo: Memo): List<MemoFileFacts> =
        if (memo.imported) {
            val rows = reader.originals(memo.id)
            val current = files.currentVersions(rows.map { it.fileId })
            rows.map { facts(it, memo.classificationRank, current[it.fileId]?.id) }
        } else {
            emptyList()
        }

    /** An original's own level when stricter, else the memo's; the main original stands where a document would. */
    private fun facts(
        row: LegacyFileRow,
        memoRank: Int,
        currentVersionId: UUID?,
    ) = MemoFileFacts(row.fileId, row.memoId, row.classificationRank ?: memoRank, currentVersionId, row.role == LegacyFileRole.MAIN)
}
