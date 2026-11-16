package com.memo.files

import com.memo.common.events.TenantScopedEvent
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Derived content of a file version (ADR-0045): a PDF to preview, the text to compare and search. */
enum class RenditionKind { PDF, TEXT }

/** Where a rendition is: built in the background, then done, failed (after retries) or not possible for its type. */
enum class RenditionState { PENDING, DONE, FAILED, NOT_POSSIBLE }

/**
 * A rendition became DONE, published synchronously in the transaction that records it: the owning module may use it (for
 * example the search index reads a TEXT rendition, ADR-0054). Ids only.
 */
data class RenditionDone(
    override val tenantId: UUID,
    val purposeKey: String,
    val ownerId: String?,
    val fileId: UUID,
    val versionId: UUID,
    val kind: RenditionKind,
) : TenantScopedEvent

/** The plaintext of a version as a port reads it: its type, its size and a fresh stream each time it is opened. */
class RenditionInput(
    val name: String,
    val type: FileType,
    val sizeBytes: Long,
    private val opener: () -> InputStream,
) {
    fun open(): InputStream = opener()
}

/** What a port answers: the content was written, a failure that retrying cannot fix, or one worth retrying. */
sealed interface RenditionOutcome {
    /** The output was written; [truncated] when it was cut at a limit (text only). */
    data class Written(
        val truncated: Boolean = false,
    ) : RenditionOutcome

    /** [code] names why, for example SIZE, TIMEOUT, MALFORMED or UNSUPPORTED. */
    data class Final(
        val code: String,
    ) : RenditionOutcome

    data class Retry(
        val code: String,
    ) : RenditionOutcome
}

/**
 * Converts a document to PDF (ADR-0045). Implemented by the editor adapter (ONLYOFFICE's conversion API); without an
 * implementation, PDF renditions are not possible. Writes the PDF to [out]; never fetches anything by URL.
 */
interface RenditionConverter {
    /** The types it can convert to PDF. */
    val pdfTypes: Set<FileType>

    fun toPdf(
        input: RenditionInput,
        key: String,
        out: OutputStream,
    ): RenditionOutcome

    /**
     * Converts to an archival PDF (PDF/A) for final records (ADR-0053); which part and level the converter writes is
     * checked by the caller's validator. [key] must differ from the one used for [toPdf]. Unsupported by default.
     */
    fun toPdfA(
        input: RenditionInput,
        key: String,
        out: OutputStream,
    ): RenditionOutcome = RenditionOutcome.Final("UNSUPPORTED")
}

/**
 * Extracts the plain text of a document (ADR-0045), through an isolated service. Writes UTF-8 text to [out], at most
 * [maxChars] characters; longer text is cut and reported as truncated.
 */
interface TextExtractor {
    val textTypes: Set<FileType>

    fun text(
        input: RenditionInput,
        maxChars: Long,
        out: OutputStream,
    ): RenditionOutcome
}

/** A rendition's state as owning modules and the API see it. */
data class RenditionInfo(
    val kind: RenditionKind,
    val state: RenditionState,
    val error: String?,
    val pages: Int?,
    val sizeBytes: Long?,
    val truncated: Boolean,
)

/**
 * Derived content for owning modules (for example the memo's version comparison). Inside a tenant scope; no access check:
 * the owning module has decided who may see the original.
 */
interface FileRenditions {
    /** The renditions of [versionId] that exist (none for a purpose that wants none). In a tenant transaction. */
    fun of(versionId: java.util.UUID): List<RenditionInfo>

    /** Writes the plaintext of the [kind] rendition of [versionId] to [out]; false when it is not `DONE`. Outside a transaction. */
    fun write(
        versionId: java.util.UUID,
        kind: RenditionKind,
        out: OutputStream,
    ): Boolean

    /**
     * How many renditions of files of [purposeKey] are PENDING in the tenant, counted up to [atMost] and no further, so
     * a backlog check stays cheap (an import's throttle, design E06 §5.3). In a tenant transaction.
     */
    fun pendingCount(
        purposeKey: String,
        atMost: Int,
    ): Int

    /** How many renditions of [versionIds] are PENDING (an import's report waits for none, E06 §5.4). In a transaction. */
    fun pendingCount(versionIds: Collection<java.util.UUID>): Int

    /**
     * The state of each rendition of [versionIds] that exists, by version and kind, in one query (an import's report counts
     * them, E06 §8). A version or kind without a rendition is absent. In a tenant transaction.
     */
    fun states(versionIds: Collection<java.util.UUID>): Map<java.util.UUID, Map<RenditionKind, RenditionState>>
}
