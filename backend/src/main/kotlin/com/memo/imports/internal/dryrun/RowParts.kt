package com.memo.imports.internal.dryrun

import com.memo.imports.internal.IntakeState
import com.memo.imports.internal.MappingKind
import com.memo.memo.LegacyApproval
import com.memo.memo.LegacyFileRole
import com.memo.memo.LegacyOriginal
import com.memo.memo.LegacyPerson
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.HexFormat
import java.util.Locale
import java.util.UUID

/** The findings of one Memos row as its parts are assembled; problems on its Files and Approvals rows count for it. */
internal class RowFindings(
    private val memoRow: Int,
) {
    val all = mutableListOf<Finding>()

    fun add(finding: Finding) {
        all += finding
    }

    fun memo(
        level: ProblemLevel,
        code: String,
        column: String?,
        params: Map<String, Any> = emptyMap(),
    ) = add(Finding(level, code, ProblemSheet.MEMOS, memoRow, column, params))

    fun error(
        code: String,
        column: String?,
        params: Map<String, Any> = emptyMap(),
    ) = memo(ProblemLevel.ERROR, code, column, params)

    fun file(
        rowNumber: Int,
        code: String,
        column: String,
        params: Map<String, Any> = emptyMap(),
        intakeId: UUID? = null,
    ) = add(Finding(ProblemLevel.ERROR, code, ProblemSheet.FILES, rowNumber, column, params, intakeId))

    fun approval(
        rowNumber: Int,
        code: String,
        column: String,
    ) = add(Finding(ProblemLevel.ERROR, code, ProblemSheet.APPROVALS, rowNumber, column))
}

/** A legacy value's target through the batch's mappings, reporting what stops it (unmapped, unconfirmed). */
internal class MappingLookup(
    private val ctx: ValidationContext,
) {
    /**
     * The target of [value] through [kind]'s mapping, unconfirmed ones included (their rows are refused with
     * `MAPPING_UNCONFIRMED`, and checked as mapped). A value too long to be a mapping is left to the length check.
     */
    fun resolve(
        kind: MappingKind,
        value: String,
        unmapped: String,
        report: (String, Map<String, Any>) -> Unit,
    ): String? {
        val mapping = ctx.mapping(kind, value)
        val params = mapOf("kind" to kind.name, "value" to value.take(DryRunLimits.MAX_PARAM))
        when {
            value.length > MAX_VALUE -> Unit
            mapping?.target == null -> report(unmapped, params)
            !mapping.confirmed -> report("MAPPING_UNCONFIRMED", params)
            else -> Unit
        }
        return mapping?.target
    }

    private companion object {
        /** `imports_mapping.legacy_value`'s bound. */
        const val MAX_VALUE = 500
    }
}

/** The originals of a Memos row: its Files rows, the originals they matched, and their levels (design E06 §5.2, §6.3). */
internal class OriginalsPart(
    private val lookup: MappingLookup,
    private val findings: RowFindings,
) {
    /** Each usable Files row as an original, with its row number. */
    fun of(files: List<StoredFile>): List<Pair<LegacyOriginal, Int>> =
        files.mapNotNull { file ->
            val at = file.staged.rowNumber
            val role =
                file.staged.role
                    ?.trim()
                    ?.uppercase(Locale.ROOT)
            val fileRole = LegacyFileRole.entries.firstOrNull { it.name == role }
            val path = file.staged.path
            when {
                role == null -> findings.file(at, "VALUE_REQUIRED", "role")
                fileRole == null -> findings.file(at, "VALUE_INVALID", "role")
                path == null -> findings.file(at, "VALUE_REQUIRED", "path")
                else -> Unit
            }
            if (path != null) received(file)
            val level =
                file.staged.classification?.let { value ->
                    lookup
                        .resolve(MappingKind.CLASSIFICATION, value, "CLASSIFICATION_UNMAPPED") { code, params ->
                            findings.file(at, code, "classification", params)
                        }?.let(RowAssembly::uuid)
                }
            if (fileRole == null ||
                path == null
            ) {
                null
            } else {
                LegacyOriginal(fileRole, file.staged.title ?: path.substringAfterLast('/'), path, level) to at
            }
        }

    /** Whether the matched original was received, whole and as expected. */
    private fun received(file: StoredFile) {
        val at = file.staged.rowNumber
        val original = file.original
        val params =
            mapOf(
                "path" to
                    file.staged.path
                        .orEmpty()
                        .take(DryRunLimits.MAX_PARAM),
            )
        val code =
            when {
                file.match == PathMatch.AMBIGUOUS -> "FILE_AMBIGUOUS"
                original == null || original.state == IntakeState.EXPECTED || original.state == IntakeState.PURGED -> "FILE_NOT_RECEIVED"
                original.state == IntakeState.REFUSED -> "FILE_REFUSED"
                original.sizeBytes == 0L -> "FILE_EMPTY"
                else -> null
            }
        if (code != null) {
            val reason = original?.refusal?.takeIf { code == "FILE_REFUSED" }
            findings.file(at, code, "path", params + listOfNotNull(reason?.let { "reason" to it }), original?.intakeId)
        } else {
            hash(file, checkNotNull(original), params)
        }
        if (file.match == PathMatch.CASE && original != null) {
            val matched = params + ("matched" to original.path.take(DryRunLimits.MAX_PARAM))
            findings.add(Finding(ProblemLevel.WARNING, "PATH_CASE_MATCH", ProblemSheet.FILES, at, "path", matched, original.intakeId))
        }
    }

    /** A SHA-256 given in the Files row must be the received original's. */
    private fun hash(
        file: StoredFile,
        original: MatchedOriginal,
        params: Map<String, Any>,
    ) {
        val expected =
            file.staged.sha256
                ?.trim()
                ?.lowercase(Locale.ROOT) ?: return
        when {
            !SHA256.matches(expected) -> findings.file(file.staged.rowNumber, "VALUE_INVALID", "sha256")
            expected != original.sha256 -> findings.file(file.staged.rowNumber, "FILE_HASH_MISMATCH", "sha256", params, original.intakeId)
            else -> Unit
        }
    }

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

/** Where a person's columns are: the initiator's on the Memos row, an approver's on an Approvals row. */
internal class PersonPlace(
    val sheet: ProblemSheet,
    val rowNumber: Int,
    val name: String,
    val email: String,
    val id: String,
)

/** The people of a Memos row: its initiator and its legacy approvals (design E06 §6.4). */
internal class PeoplePart(
    private val ctx: ValidationContext,
    private val findings: RowFindings,
) {
    /** Each Approvals row with a readable order, with its row number. */
    fun approvals(approvals: List<StoredApproval>): List<Pair<Int, LegacyApproval>> =
        approvals.mapNotNull { stored ->
            val a = stored.staged
            val ordinal = a.ordinal?.trim()?.toIntOrNull()
            val decided = a.decidedOn?.let(RowAssembly::parseDate)
            when {
                a.ordinal == null -> findings.approval(a.rowNumber, "VALUE_REQUIRED", "order")
                ordinal == null -> findings.approval(a.rowNumber, "VALUE_INVALID", "order")
                else -> Unit
            }
            if (a.decidedOn != null && decided == null) findings.approval(a.rowNumber, "DATE_INVALID", "decided_on")
            ordinal?.let {
                val place = PersonPlace(ProblemSheet.APPROVALS, a.rowNumber, "name", "email", "legacy_id")
                a.rowNumber to
                    LegacyApproval(it, person(a.name, a.email, a.legacyId, place), a.decision.orEmpty(), a.position, decided, a.remark)
            }
        }

    /** A person as given, linked only through a confirmed PERSON mapping of their email or legacy id; never by a name. */
    fun person(
        name: String?,
        email: String?,
        legacyId: String?,
        place: PersonPlace,
    ): LegacyPerson {
        val mapping = PersonKeys.of(email, legacyId)?.let { ctx.mapping(MappingKind.PERSON, it) }
        val linked = mapping?.target?.takeIf { mapping.confirmed }?.let(RowAssembly::uuid)
        when {
            mapping != null && !mapping.confirmed -> {
                val params = mapOf("kind" to MappingKind.PERSON.name, "value" to mapping.legacyValue.take(DryRunLimits.MAX_PARAM))
                val column = if (email.isNullOrBlank()) place.id else place.email
                findings.add(Finding(ProblemLevel.ERROR, "MAPPING_UNCONFIRMED", place.sheet, place.rowNumber, column, params))
            }

            linked == null && !name.isNullOrBlank() -> {
                findings.add(Finding(ProblemLevel.WARNING, "PERSON_NOT_LINKED", place.sheet, place.rowNumber, place.name))
            }

            else -> {}
        }
        return LegacyPerson(name.orEmpty(), email, legacyId, linked)
    }
}

/**
 * The row fingerprint (ADR-0055): SHA-256 of the canonical row, its approvals' values and its files' values with the
 * received originals' hashes, in key or row order. Row numbers are left out, so moving a row in the sheet changes nothing.
 */
internal object RowFingerprint {
    private const val UNIT = "\u001f"
    private const val RECORD = "\u001e"

    fun of(
        cells: Map<String, String>,
        approvals: List<StoredApproval>,
        files: List<StoredFile>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")

        fun put(vararg parts: String?) = digest.update((parts.joinToString(UNIT) { it.orEmpty() } + RECORD).toByteArray())
        cells.toSortedMap().forEach { (column, value) -> put("m", column, value) }
        approvals.forEach { (_, a) -> put("a", a.ordinal, a.name, a.email, a.legacyId, a.position, a.decision, a.decidedOn, a.remark) }
        files.forEach { f -> put("f", f.staged.role, f.staged.path, f.staged.title, f.staged.classification, f.original?.sha256) }
        return HexFormat.of().formatHex(digest.digest())
    }
}

/** Dates as the sheets give them. */
internal object LegacyDateText {
    /** `yyyy-mm-dd`, or a date with a time (`2019-03-20T10:30`), whose day is kept; nothing else is guessed. */
    fun parse(text: String): LocalDate? =
        try {
            LocalDate.parse(text.trim().substringBefore('T'))
        } catch (_: DateTimeParseException) {
            null
        }
}
