package com.memo.imports.internal.report

import io.swagger.v3.oas.annotations.media.Schema

/** Files and bytes. */
data class FilesAndBytes(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val files: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val bytes: Long,
) {
    operator fun plus(other: FilesAndBytes) = FilesAndBytes(files + other.files, bytes + other.bytes)

    companion object {
        val NONE = FilesAndBytes(0, 0)
    }
}

/** The Memos rows (design E06 §8): read = imported + skipped + rejected + failed + not started. */
data class RowTotals(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val read: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val imported: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val skipped: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val rejected: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val failed: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val notStarted: Int,
    /** Rejected rows by the codes of their errors (a row with two codes counts under both). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val rejectedByCode: Map<String, Int>,
)

/** The Files rows: listed = matched (to a received original) + not received + ambiguous. */
data class FileRowTotals(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val listed: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val matched: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val notReceived: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val ambiguous: Int,
)

/**
 * The originals received: registered = stored + refused + never uploaded; stored = imported + held back (only rows not
 * imported use them) + unreferenced. Each in files and bytes.
 */
data class OriginalTotals(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val registered: FilesAndBytes,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val stored: FilesAndBytes,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val refused: FilesAndBytes,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val neverUploaded: FilesAndBytes,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val imported: FilesAndBytes,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val heldBack: FilesAndBytes,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val unreferenced: FilesAndBytes,
)

/**
 * One kind of rendition of the imported originals: originals = done + failed + not possible + pending + not needed (a
 * PDF or a picture is its own preview). Pending is non-zero only when the report was built at the 72-hour cap.
 */
data class RenditionTotals(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val originals: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val done: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val failed: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val notPossible: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val pending: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val notNeeded: Int,
)

/**
 * Hashes: the imported originals and how many the verification pass checked (they must be equal), its mismatches and
 * other problems (they must be 0), and the originals refused at promotion because their hash differed from intake.
 */
data class FixityTotals(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val importedOriginals: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val verified: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val mismatches: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val verifyProblems: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val promotionMismatches: Int,
)

/** The reconciliation report's counts (design E06 §8). */
data class ReportTotals(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val rows: RowTotals,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val files: FileRowTotals,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val originals: OriginalTotals,
    /** By kind: PDF and TEXT. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val renditions: Map<String, RenditionTotals>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val fixity: FixityTotals,
    /** Originals promoted for rows that then failed: stored under a memo id no memo has (expected 0). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val storedNotBound: Int,
    /** Problems by level, then by code. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val problems: Map<String, Map<String, Int>>,
)

/** Whether the report balances, and BagIt's "complete" and "valid" (design E06 §8). */
data class ReportBalance(
    /** The lines that do not balance, by name; empty when balanced. */
    val failing: List<String>,
    val complete: Boolean,
    val valid: Boolean,
) {
    val balanced: Boolean get() = failing.isEmpty()

    companion object {
        const val ROWS = "ROWS"
        const val FILES_LISTED = "FILES_LISTED"
        const val ORIGINALS_REGISTERED = "ORIGINALS_REGISTERED"
        const val ORIGINALS_STORED = "ORIGINALS_STORED"
        const val RENDITIONS = "RENDITIONS_"
        const val FIXITY = "FIXITY"

        /**
         * Each balance line of [totals]; complete when every Files row has a received original and every stored original is
         * accounted for; valid when every hash held at intake, at promotion and in the verification pass.
         */
        fun of(totals: ReportTotals): ReportBalance {
            val rows = totals.rows
            val files = totals.files
            val originals = totals.originals
            val fixity = totals.fixity
            val stored = originals.imported + originals.heldBack + originals.unreferenced == originals.stored
            val failing =
                listOfNotNull(
                    ROWS.takeIf { rows.read != rows.imported + rows.skipped + rows.rejected + rows.failed + rows.notStarted },
                    FILES_LISTED.takeIf { files.listed != files.matched + files.notReceived + files.ambiguous },
                    ORIGINALS_REGISTERED.takeIf {
                        originals.registered != originals.stored + originals.refused + originals.neverUploaded
                    },
                    ORIGINALS_STORED.takeUnless { stored },
                ) +
                    totals.renditions
                        .filterValues { it.originals != it.done + it.failed + it.notPossible + it.pending + it.notNeeded }
                        .keys
                        .sorted()
                        .map { RENDITIONS + it } +
                    listOfNotNull(
                        FIXITY.takeIf {
                            fixity.verified != fixity.importedOriginals || fixity.mismatches > 0 || fixity.verifyProblems > 0
                        },
                    )
            val complete = files.notReceived == 0 && files.ambiguous == 0 && stored
            val valid = fixity.mismatches == 0 && fixity.verifyProblems == 0 && fixity.promotionMismatches == 0
            return ReportBalance(failing, complete, valid)
        }
    }
}
