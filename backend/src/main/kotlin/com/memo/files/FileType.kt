package com.memo.files

/**
 * A file type the files service can recognise from content (ADR-0029), with the name extensions that may carry it.
 * Purposes allow types from this list; types that are recognised only to be refused (macro-enabled Office, the generic
 * OLE2 container) are listed so that the rejection names them. The [LEGACY] types are allowed only by purposes that
 * accept legacy formats (ADR-0055).
 */
enum class FileType(
    val mediaType: String,
    val extensions: Set<String>,
) {
    PDF("application/pdf", setOf("pdf")),
    PNG("image/png", setOf("png")),
    JPEG("image/jpeg", setOf("jpg", "jpeg")),
    WEBP("image/webp", setOf("webp")),
    TEXT("text/plain", setOf("txt", "csv")),
    ZIP("application/zip", setOf("zip")),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", setOf("docx")),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", setOf("xlsx")),
    PPTX("application/vnd.openxmlformats-officedocument.presentationml.presentation", setOf("pptx")),
    ODT("application/vnd.oasis.opendocument.text", setOf("odt")),
    ODS("application/vnd.oasis.opendocument.spreadsheet", setOf("ods")),
    ODP("application/vnd.oasis.opendocument.presentation", setOf("odp")),

    // Legacy formats (ADR-0055), allowed only by purposes that accept them. The Tika server names the OLE2 subtypes.
    DOC("application/msword", setOf("doc")),
    XLS("application/vnd.ms-excel", setOf("xls")),
    PPT("application/vnd.ms-powerpoint", setOf("ppt")),
    RTF("application/rtf", setOf("rtf")),
    TIFF("image/tiff", setOf("tif", "tiff")),

    // Recognised, never allowed: macros, or an OLE2 container whose subtype was not asked of the Tika server.
    DOCM("application/vnd.ms-word.document.macroEnabled.12", setOf("docm")),
    XLSM("application/vnd.ms-excel.sheet.macroEnabled.12", setOf("xlsm")),
    PPTM("application/vnd.ms-powerpoint.presentation.macroEnabled.12", setOf("pptm")),
    OLE2("application/x-tika-msoffice", setOf("doc", "xls", "ppt", "msg")),
    ;

    companion object {
        private val byMediaType = entries.associateBy { it.mediaType }

        fun ofMediaType(mediaType: String): FileType? = byMediaType[mediaType]

        /** Types that may be shown inline when a purpose allows it: they cannot run script in a browser. */
        val INLINE_SAFE: Set<FileType> = setOf(PDF, PNG, JPEG, WEBP)

        /** Types never allowed by any purpose. */
        val NEVER_ALLOWED: Set<FileType> = setOf(DOCM, XLSM, PPTM, OLE2)

        /** Old Office documents inside an OLE2 container: only the Tika server names them, never the magic alone. */
        val OLE2_DOCUMENTS: Set<FileType> = setOf(DOC, XLS, PPT)

        /** Types allowed only by purposes that accept legacy formats (ADR-0055). */
        val LEGACY: Set<FileType> = OLE2_DOCUMENTS + setOf(RTF, TIFF)
    }
}
