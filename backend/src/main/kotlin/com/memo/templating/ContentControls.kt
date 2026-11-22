package com.memo.templating

import java.io.InputStream

/**
 * A content control (`w:sdt`) found in a document's body (ADR-0066): its [tag] and [lock] values (null when it has
 * none), its [title], and whether it is a block-level control directly in the body (not in a paragraph, table or another
 * control).
 */
data class FoundControl(
    val tag: String?,
    val lock: String?,
    val title: String?,
    val bodyLevel: Boolean,
)

/** One paragraph of generated text to put in a control. */
data class ControlParagraph(
    val text: String,
    val bold: Boolean = false,
)

/**
 * What is left unresolved in a document's body (design E06 §1.6.1): tracked changes (insertions, deletions, moves and
 * formatting changes, as review mode saves them) and comments, counted in `word/document.xml`.
 */
data class DocumentMarks(
    val trackedChanges: Int,
    val comments: Int,
) {
    val clean: Boolean get() = trackedChanges == 0 && comments == 0
}

/** The document is not a readable .docx, or the control asked for is not in it. */
class ContentControlException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The item controls of a minutes document (ADR-0066): reading them by streaming, replacing one control's content for a
 * reader's copy, and putting a missing control back. Pure (no Spring, no database); throws [ContentControlException].
 */
interface DocxControls {
    /**
     * Every content control of `word/document.xml` in document order, read with StAX (no DOM), so a 200-page file is
     * read in well under a second. Its size is bounded as POI's packages are ([com.memo.common.poi.PoiLimits]).
     */
    fun scan(docx: InputStream): List<FoundControl>

    /**
     * [docx] with the content of each body-level control whose tag is a key of [contents] replaced by those paragraphs.
     * The control itself, with its tag, title and lock, stays. Every tag must be found exactly once.
     */
    fun replace(
        docx: InputStream,
        contents: Map<String, List<ControlParagraph>>,
    ): ByteArray

    /**
     * [docx] with the body-level control tagged [tag] copied from [source] (a fill of the same template) and inserted
     * right after the control tagged [after], or before the first body-level control when [after] is null. Refused when
     * the copied content refers to parts of [source] (pictures, links), which the target cannot resolve.
     */
    fun restore(
        docx: InputStream,
        source: InputStream,
        tag: String,
        after: String?,
    ): ByteArray

    /**
     * [docx] with the body-level control tagged [tag] replaced, content and properties, by the one tagged so in [source]
     * (a fresh fill of the same template), in the same place: an item filled again from its decision and notes. Every
     * other control and everything between them stay as they are. Refused as [restore] is when the copied content refers
     * to parts of [source]; the tag must be found exactly once in each.
     */
    fun refill(
        docx: InputStream,
        source: InputStream,
        tag: String,
    ): ByteArray

    /** The tracked changes and comments left in [docx]'s body, read with StAX as [scan] reads it. */
    fun marks(docx: InputStream): DocumentMarks
}

/** A minutes document made into a reader's copy (ADR-0066), pure; throws [ContentControlException]. */
interface DocxCopies {
    /**
     * [docx] made fit to be a reader's copy once its controls are replaced (ADR-0066): footnotes, endnotes and comments the
     * body no longer refers to are dropped, and the document properties (title, author, company, custom ones) are emptied.
     */
    fun forCopy(docx: ByteArray): ByteArray

    /**
     * Every text of [docx]'s parts (body, headers, footers, notes and comments, deleted text included), a paragraph a line:
     * what a copy's leak check reads.
     */
    fun text(docx: InputStream): String
}
