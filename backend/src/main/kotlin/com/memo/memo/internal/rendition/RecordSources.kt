package com.memo.memo.internal.rendition

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileContents
import com.memo.files.FileType
import com.memo.files.Files
import com.memo.files.RenditionConverter
import com.memo.files.RenditionInput
import com.memo.files.RenditionOutcome
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.util.UUID
import java.nio.file.Files as Paths

/** A converter that could not answer now: the job is retried (design E04 §11). */
class RecordRetry(
    val code: String,
) : RuntimeException(code)

/** A planned part ready for the assembler, and whether it stands in for a file that could not be included. */
data class PreparedPart(
    val part: RecordPart,
    val placeholder: Boolean,
)

/**
 * Turns planned parts into the assembler's parts (design E04 §3, step 2): source files are copied from the files service
 * into the spool; PDFs and pictures go in as they are (the assembler normalises them); Office documents are converted to
 * PDF/A by the converter; anything else, or a conversion that fails for good, becomes its placeholder pages. Runs
 * outside a transaction, in the tenant's scope.
 */
@Component
class RecordSources(
    private val files: Files,
    private val contents: FileContents,
    private val converter: ObjectProvider<RenditionConverter>,
    private val transactions: TenantTransactions,
) {
    fun prepare(
        planned: PlannedPart,
        position: Int,
        renditionId: UUID,
        spool: Path,
    ): PreparedPart =
        when (planned) {
            is PlannedPart.Pages -> PreparedPart(RecordPart.Generated(planned.title, planned.label, planned.document), false)
            is PlannedPart.Source -> source(planned, position, renditionId, spool)
        }

    private fun source(
        planned: PlannedPart.Source,
        position: Int,
        renditionId: UUID,
        spool: Path,
    ): PreparedPart {
        val type = planned.file.type
        val pdfa = converter.ifAvailable?.takeIf { type != null && type in it.pdfTypes }
        val fallback = PreparedPart(RecordPart.Generated(planned.title, planned.label, planned.fallback), true)
        return when {
            type == FileType.PDF -> {
                PreparedPart(RecordPart.Pdf(planned.title, planned.label, copy(planned.file, spool, position), planned.fallback), false)
            }

            type == FileType.PNG || type == FileType.JPEG -> {
                PreparedPart(RecordPart.Image(planned.title, planned.label, copy(planned.file, spool, position), planned.fallback), false)
            }

            pdfa != null && type != null -> {
                convert(planned, type, pdfa, position, renditionId, spool) ?: fallback
            }

            else -> {
                fallback
            }
        }
    }

    /** The converter's PDF/A of the source, or null when it refuses for good (the placeholder then stands in). */
    private fun convert(
        planned: PlannedPart.Source,
        type: FileType,
        converter: RenditionConverter,
        position: Int,
        renditionId: UUID,
        spool: Path,
    ): PreparedPart? {
        val original = copy(planned.file, spool, position)
        val out = spool.resolve("converted-$position.pdf")
        val input = RenditionInput(planned.file.name, type, planned.file.sizeBytes) { Paths.newInputStream(original) }
        val outcome = Paths.newOutputStream(out).use { converter.toPdfA(input, "record-$renditionId-$position", it) }
        return when (outcome) {
            is RenditionOutcome.Written -> PreparedPart(RecordPart.Pdf(planned.title, planned.label, out, planned.fallback), false)
            is RenditionOutcome.Retry -> throw RecordRetry("CONVERTER_${outcome.code}")
            is RenditionOutcome.Final -> null
        }
    }

    private fun copy(
        source: SourceFile,
        spool: Path,
        position: Int,
    ): Path {
        val version =
            checkNotNull(transactions.readInTenant(TenantContext.current()) { files.version(source.versionId) }) {
                "file version ${source.versionId} missing"
            }
        val file = spool.resolve("source-$position")
        Paths.newOutputStream(file).use { contents.write(version, it) }
        return file
    }
}
