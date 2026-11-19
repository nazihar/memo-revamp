package com.memo.templating

import java.io.InputStream
import java.nio.channels.SeekableByteChannel

/**
 * Where something is in a template: the [part] as an admin knows it ("Body", "Header 1", "Footer 2", "Footnotes",
 * "Endnotes") and the [paragraph] in it, counted from 1 in document order (paragraphs in tables and text boxes count).
 */
data class TemplateLocation(
    val part: String,
    val paragraph: Int,
)

/** Content a template may never carry (ADR-0048). The upload is refused, never cleaned silently. */
enum class RefusalRule {
    /** Not a Word document package, or one we cannot read safely. */
    NOT_A_DOCUMENT,

    /** VBA projects, macro data or key-map customisations. */
    MACROS,
    ACTIVEX,

    /** OLE objects and other embedded packages. */
    OLE_OBJECT,

    /** `altChunk`: content imported from another file when the document is opened. */
    ALT_CHUNK,
    ATTACHED_TEMPLATE,

    /** A relationship to something outside the package, other than a hyperlink (an external image, a frame). */
    EXTERNAL_RELATIONSHIP,

    /** A field that pulls in other content or runs something: `INCLUDETEXT`, `INCLUDEPICTURE`, `LINK`, `DDE`, … */
    FIELD_CODE,

    /** A mail-merge data source. */
    MAIL_MERGE,

    /** Sub-documents, frames, add-ins or a part of a kind we do not know. */
    UNSUPPORTED_PART,
}

/** One refused thing and where it is: a part name (`word/vbaProject.bin`) or a [TemplateLocation] as text. */
data class TemplateRefusal(
    val rule: RefusalRule,
    val where: String,
)

enum class TagKind {
    /** `{{name}}`: replaced by a value (also the loop tag `{{approvals}}`). */
    VALUE,

    /** `{{?name}}`: the start of a block shown only when the value is not blank. */
    SECTION_START,

    /** `{{/name}}`: the end of that block. */
    SECTION_END,

    /** `[name]`: a field of a repeated table row. */
    ROW_FIELD,
}

/** A table row in a part: the table's number (document order, nested tables too) and the row's, both from 1. */
data class TableRow(
    val part: String,
    val table: Int,
    val row: Int,
)

/** A tag found in a template, with its row when it is in a table, and the text of its paragraph around it. */
data class TemplateTag(
    val kind: TagKind,
    val name: String,
    val location: TemplateLocation,
    val row: TableRow?,
    val excerpt: String,
)

enum class TemplateProblemCode {
    /** `{{ memo.number }}`, `{{memo..date}}`, a `{{` without `}}`, non-ASCII names. */
    MALFORMED_TAG,

    /** A tag kind we do not offer: pictures (`@`), tables (`#`), lists (`*`), includes (`+`), aliases (`=`). */
    UNSUPPORTED_TAG,

    /** Not one of the tags of this kind of document. */
    UNKNOWN_TAG,

    /** A `{{?name}}` without its `{{/name}}`, or the other way round, or blocks that overlap. */
    UNBALANCED_SECTION,

    /** A loop tag that is not in a table, or whose next row has no `[fields]`. */
    LOOP_WITHOUT_ROW,

    /** A `[field]` that the loop does not have. */
    UNKNOWN_ROW_FIELD,

    /** A `{{tag}}` inside a repeated row: only `[fields]` may be there. */
    TAG_IN_LOOP_ROW,
    TOO_MANY_TAGS,
}

/** Something to fix before a template can be used, with where it is and the text around it. */
data class TemplateProblem(
    val code: TemplateProblemCode,
    val location: TemplateLocation,
    /** At most [EXCERPT_CHARS] characters of the paragraph around the problem. */
    val excerpt: String,
    val tag: String? = null,
    /** The closest known tag, when there is one. */
    val suggestion: String? = null,
) {
    companion object {
        const val EXCERPT_CHARS = 80
    }
}

/** What [DocxTemplates.scan] found: refusals (any one refuses the upload), tags in document order, malformed tags. */
data class TemplateScan(
    val refusals: List<TemplateRefusal>,
    val tags: List<TemplateTag>,
    val problems: List<TemplateProblem>,
)

/**
 * The tags a kind of document offers: value tags, the subset that may open a block, and loops with their row fields.
 * Names are ASCII, dotted (`memo.number`, `meta.amount`).
 */
data class TagVocabulary(
    val values: Set<String>,
    val sections: Set<String>,
    val loops: Map<String, Set<String>>,
)

/**
 * Pre-formatted text for every value tag, the rows of each loop (field name to text), and the elements of each block
 * (ADR-0066): a `{{?name}}…{{/name}}` section whose name is a block repeats once per element, with the value tags,
 * sections and table-row loops inside it resolved against the element. Blocks are one level deep: an element's own
 * [blocks] are not used.
 */
data class TemplateData(
    val values: Map<String, String>,
    val rows: Map<String, List<Map<String, String>>> = emptyMap(),
    val blocks: Map<String, List<TemplateData>> = emptyMap(),
    /** On a block element: wrap the element's rendered block in this content control. Ignored elsewhere. */
    val control: ControlMark? = null,
)

/**
 * A block-level rich-text content control (`w:sdt`) around one block element (ADR-0066): its [tag] (`memo-item:<id>`),
 * its [title] as Word and ONLYOFFICE show it, and the lock `sdtLocked` (the control cannot be deleted; its content can
 * be edited). Tags are 1 to [MAX_TAG] letters, digits and `:._-`.
 */
data class ControlMark(
    val tag: String,
    val title: String,
) {
    init {
        require(TAG.matches(tag)) { "a control tag is 1 to $MAX_TAG letters, digits and :._-" }
        require(title.length <= MAX_TITLE) { "a control title is at most $MAX_TITLE characters" }
    }

    companion object {
        const val MAX_TAG = 64
        const val MAX_TITLE = 64
        private val TAG = Regex("^[A-Za-z0-9:._-]{1,$MAX_TAG}$")
    }
}

/** Reads templates: what they contain and whether they fit a [TagVocabulary]. No Spring or database needed. */
interface DocxTemplates {
    /**
     * Scans the .docx package that [open] reads (each call a fresh channel): its parts and relationships for refused
     * content, and its body, headers, footers, footnotes and endnotes for tags. Never throws for bad content: an
     * unreadable package is a [RefusalRule.NOT_A_DOCUMENT] refusal.
     */
    fun scan(open: () -> SeekableByteChannel): TemplateScan

    /** [TemplateScan.problems] and every problem against [vocabulary], in document order. Empty: the template is usable. */
    fun check(
        scan: TemplateScan,
        vocabulary: TagVocabulary,
    ): List<TemplateProblem>
}

/** Why a fill did not produce a document. */
enum class FillFailure {
    /** The template is not a readable .docx, or poi-tl could not resolve its tags. */
    INVALID_TEMPLATE,

    /** A tag had no value, or used a kind we do not offer. */
    MISSING_VALUE,

    /** The fill took longer than its limit. */
    TIMEOUT,

    /** Too many fills are running on this node. */
    BUSY,
}

class TemplateFillException(
    val failure: FillFailure,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Fills a checked template (ADR-0048) with pre-formatted text: values go in as text runs, never as markup or tags.
 * Block elements with a [ControlMark] come out wrapped in their locked content control (ADR-0066); such a block's
 * `{{?name}}` and `{{/name}}` must each be in a paragraph of the body, not in a table. Bounded per node, with a time
 * limit; throws [TemplateFillException].
 */
interface TemplateFiller {
    fun fill(
        template: InputStream,
        data: TemplateData,
    ): ByteArray
}
