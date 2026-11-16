package com.memo.memo

import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/** Where a memo comes from (ADR-0055): raised here, or imported in a final state from a legacy system. Stored by name. */
enum class MemoOrigin { NATIVE, IMPORTED }

/** The final statuses a legacy memo is imported in (ADR-0055); in-flight legacy memos are refused before this. */
enum class LegacyStatus { APPROVED, REJECTED, WITHDRAWN, CANCELLED, CLOSED }

/** An original's part in the memo: its main document, or an attachment. */
enum class LegacyFileRole { MAIN, ATTACHMENT }

/**
 * A person as the legacy data names them. [userId] is set only when the person was linked by an explicit identifier (an
 * email or a mapped legacy id) confirmed in the batch's people mapping, never by a name (ADR-0055, design E06 §6.4).
 */
data class LegacyPerson(
    val name: String,
    val email: String? = null,
    val legacyId: String? = null,
    val userId: UUID? = null,
)

/** A legacy decision, kept as history: never a workflow action, task or stamp. */
data class LegacyApproval(
    val ordinal: Int,
    val person: LegacyPerson,
    val decision: String,
    val position: String? = null,
    val decidedOn: LocalDate? = null,
    val remark: String? = null,
)

/**
 * An original file of the memo. [fileId] is the stored file of purpose `memo.legacy` whose owner is the memo; it is null
 * while a row is only checked (the dry run), and required to create. A [classificationId] makes the file stricter than
 * its memo; null keeps the memo's level.
 */
data class LegacyOriginal(
    val role: LegacyFileRole,
    val title: String,
    val relativePath: String,
    val classificationId: UUID? = null,
    val fileId: UUID? = null,
)

/**
 * Where a row comes from: the batch and its Memos row, the row key (source code and legacy number as given), the row's
 * fingerprint (SHA-256, lower-case hex), the batch's time zone and who started the import.
 */
data class LegacySource(
    val batchId: UUID,
    val rowNumber: Int,
    val sourceCode: String,
    val legacyNumber: String,
    val fingerprint: String,
    val timeZone: ZoneId,
    val startedBy: UUID,
)

/** The legacy values as given, before mapping: kept beside the memo for good. */
data class LegacyAsGiven(
    val status: String,
    val type: String,
    val unit: String,
    val subject: String,
    val classification: String? = null,
)

/**
 * The row mapped to this tenant: a memo type with a published version, a final status, a classification level, a unit,
 * the reference (the legacy number with the batch's prefix, outside every number series) and the type's field values.
 */
data class LegacyMapped(
    val typeId: UUID,
    val status: LegacyStatus,
    val classificationId: UUID,
    val originUnitId: UUID,
    val reference: String,
    val metadata: Map<String, Any?> = emptyMap(),
)

/** The legacy dates, as days in the batch's time zone. */
data class LegacyDates(
    val raisedOn: LocalDate,
    val decidedOn: LocalDate? = null,
    val closedOn: LocalDate? = null,
)

/** One legacy memo to import as [memoId] (the id the import reserved for it, the owner of its originals). */
data class LegacyMemoRow(
    val memoId: UUID,
    val source: LegacySource,
    val given: LegacyAsGiven,
    val mapped: LegacyMapped,
    val dates: LegacyDates,
    val initiator: LegacyPerson,
    val approvals: List<LegacyApproval> = emptyList(),
    val originals: List<LegacyOriginal> = emptyList(),
)

/** What is wrong with a row (design E06 §9): a stable [code] and where (a column or a part of the row). */
data class LegacyProblem(
    val code: String,
    val field: String,
)

/** Thrown by [LegacyMemos.create]: the row has [problems]; nothing was written. */
class LegacyMemoRefusedException(
    val problems: List<LegacyProblem>,
) : RuntimeException("legacy memo refused: ${problems.map { it.code }.distinct()}")

/** A memo type an import may map to: its code and name as the template lists them, and its published version's field keys. */
data class LegacyType(
    val id: UUID,
    val code: String,
    val name: String,
    val fieldKeys: List<String>,
)

/** A memo already imported under a row key, with the fingerprint it was imported with. */
data class ExistingImport(
    val memoId: UUID,
    val batchId: UUID,
    val rowNumber: Int,
    val fingerprint: String,
)

/**
 * Imported memos (ADR-0055, design E06 §3.1), for the imports module. Every method runs inside a tenant transaction;
 * [create] writes in the caller's, and the others only read (so a dry run can call them in a read-only one).
 */
interface LegacyMemos {
    /** The problems that stop [row] from being imported now; empty when it can be. Reads only. */
    fun check(row: LegacyMemoRow): List<LegacyProblem>

    /**
     * Imports [row]: the memo in its final state with its legacy facts, approvals as history, originals, its linked people
     * as participants and the `memo.imported` audit entry, all in the caller's transaction. No workflow, task, stamp,
     * notification or watch. Throws [LegacyMemoRefusedException] when [check] finds a problem. Returns the memo's id.
     */
    fun create(row: LegacyMemoRow): UUID

    /** The memo imported under the row key ([sourceCode], [legacyNumber] as given), if one was. */
    fun existing(
        sourceCode: String,
        legacyNumber: String,
    ): ExistingImport?

    /** The number series of every published memo-type version, to keep legacy numbers out of numbers they may still give. */
    fun series(): List<LiveSeries>

    /** The memo types a row may map to (design E06 §6.3): active, with a published version, and that version's field keys. */
    fun types(): List<LegacyType>
}

/** Legacy numbers as a row key (ADR-0055). */
object LegacyNumbers {
    const val MAX_LENGTH = 100
    private val SPACES = Regex("\\s+")

    /** Trimmed, inner spaces collapsed, NFC, upper case: two numbers with the same key are one row key. */
    fun key(number: String): String = Normalizer.normalize(number.trim().replace(SPACES, " "), Normalizer.Form.NFC).uppercase(Locale.ROOT)
}
