package com.memo.memo.internal.legacy

import com.memo.audit.AuditLog
import com.memo.common.authz.Clearances
import com.memo.files.Files
import com.memo.memo.ExistingImport
import com.memo.memo.LegacyMemoRefusedException
import com.memo.memo.LegacyMemoRow
import com.memo.memo.LegacyMemos
import com.memo.memo.LegacyNumbers
import com.memo.memo.LegacyProblem
import com.memo.memo.LegacyStatus
import com.memo.memo.LegacyType
import com.memo.memo.LiveSeries
import com.memo.memo.internal.MemoChangeLog
import com.memo.memo.internal.MemoMetadata
import com.memo.memo.internal.MemoStore
import com.memo.memo.internal.MemoTypes
import com.memo.memo.internal.Memos
import com.memo.memo.internal.ParticipantKind
import org.jooq.exception.IntegrityConstraintViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Imported memos (ADR-0055, design E06 §3.1, §5.3): what the imports module calls to check a row, create its memo in the
 * caller's transaction, find an earlier import and read the live number series. An imported memo never touches the
 * workflow, the register, notifications, watches or final records; its linked people become participants so the read
 * rule (ADR-0050) lets them in under their clearance, and the importer does not.
 */
@Service
class LegacyMemoService(
    private val checks: LegacyChecks,
    private val writer: LegacyMemoWriter,
    private val reader: LegacyMemoReader,
    private val series: LegacySeries,
    private val types: MemoTypes,
    private val metadata: MemoMetadata,
    private val clearances: Clearances,
    private val files: Files,
    private val memos: MemoStore,
    private val changes: MemoChangeLog,
    private val audit: AuditLog,
    private val clock: Clock,
) : LegacyMemos {
    override fun check(row: LegacyMemoRow): List<LegacyProblem> = checks.problems(row)

    override fun create(row: LegacyMemoRow): UUID {
        require(FINGERPRINT.matches(row.source.fingerprint)) { "the row fingerprint is a lower-case hex SHA-256" }
        val problems = (checks.problems(row) + checks.stored(row)).distinct()
        if (problems.isNotEmpty()) throw LegacyMemoRefusedException(problems)
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        val version = checkNotNull(types.published(row.mapped.typeId))
        val rank = checkNotNull(clearances.level(row.mapped.classificationId)).rank
        val values = metadata.check(version.settings.fields, row.mapped.metadata, requireAll = false).values
        val closedAt =
            row.dates.closedOn
                ?.takeIf { row.mapped.status == LegacyStatus.CLOSED }
                ?.atStartOfDay(row.source.timeZone)
                ?.toOffsetDateTime()
        val originals = stored(row)
        uniquely {
            writer.memo(
                ImportedMemo(row, version.id, rank, values, row.given.subject.trim(), closedAt?.withOffsetSameInstant(ZoneOffset.UTC)),
                now,
            )
            writer.facts(row, now)
            writer.approvals(row.memoId, row.approvals)
            writer.originals(row.memoId, originals)
        }
        val people = participants(row, now)
        // Search follows the memo module's changes; E06 T4 indexes imported memos (until then their facts are none).
        changes.memo(row.memoId)
        originals.forEach { changes.file(row.memoId, it.fileId) }
        audited(row, originals, people)
        return row.memoId
    }

    /** `memo.imported`, in the memo's transaction: where it came from, what was stored, and who started the batch. */
    private fun audited(
        row: LegacyMemoRow,
        originals: List<StoredOriginal>,
        people: Int,
    ) = audit.record(
        "memo.imported",
        Memos.OBJECT_TYPE,
        row.memoId.toString(),
        after =
            mapOf(
                "batchId" to row.source.batchId.toString(),
                "sourceCode" to row.source.sourceCode,
                "legacyNumber" to row.source.legacyNumber,
                "rowNumber" to row.source.rowNumber,
                "fingerprint" to row.source.fingerprint,
                "originals" to
                    originals.map {
                        mapOf(
                            "fileId" to it.fileId.toString(),
                            "versionId" to it.versionId.toString(),
                            "sha256" to it.sha256,
                        )
                    },
                "typeId" to row.mapped.typeId.toString(),
                "status" to row.mapped.status.name,
                "classificationId" to row.mapped.classificationId.toString(),
                "unitId" to row.mapped.originUnitId.toString(),
                "linkedPeople" to people,
                "startedBy" to row.source.startedBy.toString(),
            ),
    )

    override fun existing(
        sourceCode: String,
        legacyNumber: String,
    ): ExistingImport? = reader.existing(sourceCode, LegacyNumbers.key(legacyNumber))

    override fun series(): List<LiveSeries> = series.all()

    override fun types(): List<LegacyType> =
        types.active().map { type ->
            LegacyType(type.id, type.code, type.name, types.published(type.id)?.let { v -> v.settings.fields.map { it.key } }.orEmpty())
        }

    /** Runs the writes; a unique key taken meanwhile becomes the matching refusal (as Spring or jOOQ reports it). */
    private fun uniquely(writes: () -> Unit) {
        try {
            writes()
        } catch (e: DuplicateKeyException) {
            throw takenOr(e)
        } catch (e: IntegrityConstraintViolationException) {
            throw takenOr(e)
        }
    }

    /**
     * The unique row key or reference was taken meanwhile (the check runs first, the index is the last guard): a refusal
     * naming which. Any other failure is returned as it is.
     */
    private fun takenOr(e: RuntimeException): RuntimeException {
        val unique = e is DuplicateKeyException || (e is IntegrityConstraintViolationException && e.sqlState() == UNIQUE_VIOLATION)
        if (!unique) return e
        val code = if (e.message.orEmpty().contains(KEY_INDEX)) "ALREADY_IMPORTED" else "NUMBER_IN_USE"
        return LegacyMemoRefusedException(listOf(LegacyProblem(code, "legacy_number")))
    }

    /** Each original with the hash and size of the version the files service stored. */
    private fun stored(row: LegacyMemoRow): List<StoredOriginal> {
        // MAIN first, then attachments in the order given.
        val ordered = row.originals.sortedBy { it.role.ordinal }
        return ordered.map { original ->
            val fileId = checkNotNull(original.fileId)
            val version = checkNotNull(files.info(fileId)?.currentVersionId?.let(files::version)) { "original $fileId has no version" }
            StoredOriginal(
                fileId = fileId,
                versionId = version.id,
                role = original.role,
                title = original.title,
                relativePath = original.relativePath,
                classificationId = original.classificationId,
                classificationRank = original.classificationId?.let { checkNotNull(clearances.level(it)).rank },
                sha256 = version.sha256,
                sizeBytes = version.sizeBytes,
            )
        }
    }

    /** Linked people become participants (INITIATOR, APPROVER); the importer never does. Returns how many were linked. */
    private fun participants(
        row: LegacyMemoRow,
        now: OffsetDateTime,
    ): Int {
        val initiator = row.initiator.userId
        val approvers = row.approvals.mapNotNull { it.person.userId }.toSet()
        initiator?.let { memos.addParticipant(row.memoId, it, ParticipantKind.INITIATOR, now) }
        approvers.forEach { memos.addParticipant(row.memoId, it, ParticipantKind.APPROVER, now) }
        return (approvers + listOfNotNull(initiator)).size
    }

    private companion object {
        val FINGERPRINT = Regex("^[0-9a-f]{64}$")
        const val KEY_INDEX = "memo_legacy_key_uk"
        const val UNIQUE_VIOLATION = "23505"
    }
}
