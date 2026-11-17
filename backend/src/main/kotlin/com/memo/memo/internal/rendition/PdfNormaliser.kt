package com.memo.memo.internal.rendition

import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import java.io.IOException

/**
 * Removes from a part what PDF/A forbids and an archival record should not carry (ADR-0053, threat model T3): document
 * and page actions, JavaScript, forms (flattened into the page when they can be), and annotations other than links with
 * plain destinations and annotations that have a normal appearance; those kept are printable and visible. What it cannot
 * fix (fonts not embedded, device colours) is left to the validator, and the part is then rasterised.
 */
object PdfNormaliser {
    private val BANNED_ANNOTATIONS = setOf("3D", "Sound", "Screen", "Movie", "FileAttachment", "RichMedia", "TrapNet")

    fun normalise(document: PDDocument) {
        val catalog = document.documentCatalog
        catalog.cosObject.removeItem(COSName.OPEN_ACTION)
        catalog.cosObject.removeItem(COSName.AA)
        catalog.cosObject.removeItem(COSName.getPDFName("NeedsRendering"))
        catalog.names?.let { names ->
            names.cosObject.removeItem(COSName.JAVA_SCRIPT)
            names.cosObject.removeItem(COSName.EMBEDDED_FILES)
        }
        forms(document)
        catalog.documentOutline = null
        catalog.cosObject.removeItem(COSName.STRUCT_TREE_ROOT)
        catalog.cosObject.removeItem(COSName.MARK_INFO)
        document.pages.forEach(::page)
    }

    /** Fields become page content where they have appearances; the form itself goes. */
    private fun forms(document: PDDocument) {
        val form = document.documentCatalog.acroForm ?: return
        try {
            form.cosObject.removeItem(COSName.XFA)
            form.flatten()
        } catch (_: IOException) {
            // A field without an appearance: dropped with the form below.
        }
        document.documentCatalog.acroForm = null
    }

    private fun page(page: PDPage) {
        page.cosObject.removeItem(COSName.AA)
        page.annotations = page.annotations.filter(::keep).onEach(::tidy)
    }

    private fun keep(annotation: PDAnnotation): Boolean =
        when {
            annotation.subtype in BANNED_ANNOTATIONS || annotation is PDAnnotationPopup -> false
            annotation is PDAnnotationLink -> annotation.action.let { it == null || it is PDActionURI || it is PDActionGoTo }
            else -> annotation.appearance?.normalAppearance != null
        }

    /** Printable, never hidden, no actions of its own, and only the normal appearance (PDF/A-2 6.3.2, 6.3.3). */
    private fun tidy(annotation: PDAnnotation) {
        annotation.isPrinted = true
        annotation.isHidden = false
        annotation.isInvisible = false
        annotation.isNoView = false
        annotation.isToggleNoView = false
        annotation.cosObject.removeItem(COSName.AA)
        if (annotation !is PDAnnotationLink) annotation.cosObject.removeItem(COSName.A)
        annotation.appearance?.normalAppearance?.let { normal ->
            annotation.appearance = PDAppearanceDictionary().apply { setNormalAppearance(normal) }
        }
    }
}
