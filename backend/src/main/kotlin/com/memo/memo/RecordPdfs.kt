package com.memo.memo

import com.memo.files.FileType
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** One part of a record made by [RecordPdfs], in order, with its bookmark title and page-label prefix (for example `M-`). */
sealed interface RecordPdfPart {
    val title: String
    val label: String

    /** Pages drawn from a report (ADR-0052's renderer: the cover, an annex). */
    data class Pages(
        override val title: String,
        override val label: String,
        val document: ReportDocument,
    ) : RecordPdfPart

    /**
     * An Office document (for example the approved minutes' .docx) converted to PDF/A by the document server (ADR-0053),
     * then normalised like any part. [name] is what the converter is given.
     */
    data class Document(
        override val title: String,
        override val label: String,
        val name: String,
        val type: FileType,
        val file: Path,
    ) : RecordPdfPart
}

/** What a record says about itself in its XMP and document information (ADR-0053). */
data class RecordPdfMetadata(
    val title: String,
    /** A number or reference naming what the record is of (for example the meeting's label). */
    val identifier: String,
    /** The source document's hash and the versions, in one line. */
    val description: String,
    val author: String,
    val created: Instant,
)

/**
 * A record to make: its [parts], its [metadata], the [textItems] that must come back from its text in order (ADR-0053's
 * text check), an optional [stamp] line drawn at the foot of every page (the approval stamp of approved minutes), and a
 * [key] unique to this build, from which the converter's keys are made (a retried build may reuse it).
 */
data class RecordPdfRequest(
    val parts: List<RecordPdfPart>,
    val metadata: RecordPdfMetadata,
    val textItems: List<String> = emptyList(),
    val stamp: String? = null,
    val key: String,
)

/** How a record came out: its pages, veraPDF's verdict (PDF/A-2u), what went in otherwise than as text, and the versions. */
data class RecordPdf(
    val pages: Int,
    val compliant: Boolean,
    val profile: String,
    val validator: String,
    /** Failed rules (clause and test number) and how often each failed. */
    val failedRules: Map<String, Int>,
    /** Why no verdict could be reached (a time limit, an unreadable file); the record then counts as not compliant. */
    val validationError: String?,
    val renderer: String,
    val rasterisedPages: Int,
    val missingGlyphs: Int,
    /** The [RecordPdfRequest.textItems] not found in the record's text. */
    val textMissing: List<String>,
)

/** The document server could not convert a part now (down, busy): the caller's job should try again later. */
class RecordPdfRetry(
    val code: String,
) : RuntimeException(code)

/** A part cannot be converted at all (refused, too large): retrying will not help. */
class RecordPdfFailed(
    val code: String,
) : RuntimeException(code)

/**
 * ADR-0053's record machinery for other modules (as [ReportPages] is), for example the minutes module's record of approved
 * minutes (ADR-0066): pages drawn by ADR-0052's renderer and Office documents converted to PDF/A, assembled into one
 * PDF/A-2u file with one output intent, our metadata, a bookmark and page labels per part, validated by veraPDF and its
 * text checked. The machinery itself stays internal to the memo module. Call outside a transaction, in the tenant's scope;
 * works in temp files, never the whole record in memory.
 */
interface RecordPdfs {
    /** Writes the record to [out]; throws [RecordPdfRetry] or [RecordPdfFailed] when a document part cannot be converted. */
    fun build(
        request: RecordPdfRequest,
        out: Path,
    ): RecordPdf
}

/** One stored record version the weekly fixity run re-hashes (ADR-0053): its file version and the hash recorded for it. */
data class FixityRecord(
    val id: UUID,
    val fileVersionId: UUID,
    val sha256: String,
    /** Where a mismatch is audited: the object's type and id, with [details] (for example the record's version). */
    val objectType: String,
    val objectId: String,
    val details: Map<String, Any?> = emptyMap(),
)

/**
 * Records of other modules that the memo module's weekly fixity run checks with its own (ADR-0053; for example the
 * approved minutes' records and copies, ADR-0066): pages in id order, each version re-hashed from storage; a mismatch is
 * audited as [mismatchAction] on the record's object, and each run as [verifiedAction] with its counts.
 */
interface RecordFixitySource {
    val verifiedAction: String
    val mismatchAction: String

    /** The object type the run's count is audited on. */
    val objectType: String

    /** At most [limit] stored records after [after] (by id), inside a tenant transaction. */
    fun page(
        after: UUID?,
        limit: Int,
    ): List<FixityRecord>
}
