package com.memo.imports.internal

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileContents
import com.memo.files.FileType
import com.memo.files.FileVersionInfo
import com.memo.files.Files
import com.memo.imports.internal.sheet.CsvSheetReader
import com.memo.imports.internal.sheet.ImportParsePool
import com.memo.imports.internal.sheet.RowSink
import com.memo.imports.internal.sheet.SheetLimits
import com.memo.imports.internal.sheet.SheetProblem
import com.memo.imports.internal.sheet.SheetProblemCode
import com.memo.imports.internal.sheet.SheetResult
import com.memo.imports.internal.sheet.SheetTable
import com.memo.imports.internal.sheet.XlsxSheetReader
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Reads a batch's metadata into rows (design E06 §7): its workbook, or its CSV files one table at a time, each on the
 * `imports-parse` pool under its own time limit. The staged content is decrypted into memory (at most 20 MiB a source);
 * nothing reaches a parser before the pipeline scanned, typed and hashed it. The dry run (T6) stages the rows the [sink]
 * receives. Call outside a transaction, with the tenant bound.
 */
@Service
class ImportSources(
    private val batches: ImportBatchStore,
    private val files: Files,
    private val contents: FileContents,
    private val transactions: TenantTransactions,
    private val pool: ImportParsePool,
) {
    fun parse(
        batchId: UUID,
        sink: RowSink,
        limits: SheetLimits = SheetLimits(),
    ): SheetResult {
        val sources =
            transactions.readInTenant(TenantContext.current()) {
                val batch = batches.find(batchId) ?: throw ImportBatchNotFoundException()
                batch.sources.associate { it.table to checkNotNull(files.version(it.versionId)) { "version ${it.versionId}" } }
            }
        val workbook = sources[SourceTable.WORKBOOK]
        val missing = TABLES.filterKeys { it !in sources }.values.filter { it.required }
        return when {
            workbook != null -> {
                workbook(workbook, sink, limits)
            }

            missing.isNotEmpty() -> {
                SheetResult(missing.map { SheetProblem(SheetProblemCode.SOURCE_MISSING, it) }, emptyMap())
            }

            else -> {
                val results =
                    TABLES.filterKeys { it in sources }.map { (source, table) ->
                        csv(table, sources.getValue(source), sink, limits)
                    }
                SheetResult(results.flatMap { it.problems }, results.fold(emptyMap()) { all, r -> all + r.rows })
            }
        }
    }

    private fun workbook(
        version: FileVersionInfo,
        sink: RowSink,
        limits: SheetLimits,
    ): SheetResult {
        val bytes = content(version, limits)
        return when {
            version.mediaType != FileType.XLSX.mediaType -> SheetResult.failed(SheetProblem(SheetProblemCode.SOURCE_KIND_MIXED))
            bytes == null -> tooLarge(null, limits)
            else -> pool.parse { deadline -> XlsxSheetReader(limits, deadline).read(bytes.inputStream(), sink) }
        }
    }

    private fun csv(
        table: SheetTable,
        version: FileVersionInfo,
        sink: RowSink,
        limits: SheetLimits,
    ): SheetResult {
        val bytes = content(version, limits)
        return when {
            version.mediaType != FileType.TEXT.mediaType -> SheetResult.failed(SheetProblem(SheetProblemCode.SOURCE_KIND_MIXED, table))
            bytes == null -> tooLarge(table, limits)
            else -> pool.parse { deadline -> CsvSheetReader(limits, deadline).read(table, bytes.inputStream(), sink) }
        }
    }

    /** The plaintext of [version], or null when it is above the source cap. */
    private fun content(
        version: FileVersionInfo,
        limits: SheetLimits,
    ): ByteArray? {
        if (version.sizeBytes > limits.maxBytes) return null
        return ByteArrayOutputStream(Math.toIntExact(version.sizeBytes)).also { contents.write(version, it) }.toByteArray()
    }

    private fun tooLarge(
        table: SheetTable?,
        limits: SheetLimits,
    ) = SheetResult.failed(SheetProblem(SheetProblemCode.MALFORMED, table, params = mapOf("limit" to limits.maxBytes)))

    private companion object {
        val TABLES =
            mapOf(
                SourceTable.MEMOS to SheetTable.MEMOS,
                SourceTable.FILES to SheetTable.FILES,
                SourceTable.APPROVALS to SheetTable.APPROVALS,
            )
    }
}
