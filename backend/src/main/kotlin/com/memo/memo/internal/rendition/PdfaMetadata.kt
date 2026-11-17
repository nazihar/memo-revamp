package com.memo.memo.internal.rendition

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.common.PDMetadata
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent
import org.apache.xmpbox.XMPMetadata
import org.apache.xmpbox.xml.XmpSerializer
import java.awt.color.ColorSpace
import java.awt.color.ICC_Profile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/** What a record says about itself, in the XMP and the document information dictionary (kept consistent, as PDF/A asks). */
data class RecordMetadata(
    val title: String,
    /** The memo's number, or its reference when unnumbered. */
    val identifier: String,
    /** The document hash and the renderer and converter versions, in one line. */
    val description: String,
    val author: String,
    val producer: String,
    val created: Instant,
)

/**
 * The PDF/A part of a document (ADR-0053): exactly one sRGB output intent (PDF/A-2 asks every intent to share one ICC
 * profile), and XMP with `pdfaid` part 2, conformance U, Dublin Core, XMP basic and Adobe PDF properties. Only predefined
 * XMP properties are used, so no extension schema is needed: the memo's number is `dc:identifier`, the hashes and
 * versions are in `dc:description`.
 */
object PdfaMetadata {
    private const val SRGB = "sRGB IEC61966-2.1"

    fun apply(
        document: PDDocument,
        metadata: RecordMetadata,
    ) {
        val catalog = document.documentCatalog
        // The JDK's own sRGB profile (ICC v2, display class): no profile file of ours to keep.
        val profile = ICC_Profile.getInstance(ColorSpace.CS_sRGB).data
        val intent = PDOutputIntent(document, ByteArrayInputStream(profile))
        intent.info = SRGB
        intent.outputCondition = SRGB
        intent.outputConditionIdentifier = SRGB
        intent.registryName = "http://www.color.org"
        catalog.outputIntents = listOf(intent)

        val created = calendar(metadata.created)
        val info = document.documentInformation
        info.title = metadata.title
        info.author = metadata.author
        info.subject = metadata.description
        info.producer = metadata.producer
        info.creator = metadata.producer
        info.creationDate = created
        info.modificationDate = created
        info.keywords = null

        val xmp = XMPMetadata.createXMPMetadata()
        xmp.createAndAddPDFAIdentificationSchema().apply {
            part = 2
            conformance = "U"
        }
        xmp.createAndAddDublinCoreSchema().apply {
            title = metadata.title
            addCreator(metadata.author)
            description = metadata.description
            identifier = metadata.identifier
            format = "application/pdf"
        }
        xmp.createAndAddXMPBasicSchema().apply {
            createDate = created
            modifyDate = created
            metadataDate = created
            creatorTool = metadata.producer
        }
        xmp.createAndAddAdobePDFSchema().producer = metadata.producer
        val bytes = ByteArrayOutputStream().also { XmpSerializer().serialize(xmp, it, true) }.toByteArray()
        catalog.metadata = PDMetadata(document).apply { importXMPMetadata(bytes) }
    }

    private fun calendar(at: Instant): Calendar =
        GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = at.toEpochMilli()
        }
}
