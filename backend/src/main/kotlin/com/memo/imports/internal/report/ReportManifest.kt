package com.memo.imports.internal.report

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.Files
import com.memo.imports.internal.ImportBatch
import org.springframework.stereotype.Component
import java.nio.file.Path
import kotlin.io.path.bufferedWriter

/**
 * The report's manifest (design E06 §8, part 3): `manifest-sha256.txt` as RFC 8493 §2.1.3 writes it, one line
 * `<sha256>  <path>` per original the import stored (those bound to imported memos and those stored but not bound),
 * the SHA-256 as the files service recorded the stored copy and the path as received (`%`, CR and LF percent-encoded).
 * Plain text, never a spreadsheet. With the tenant bound, outside a transaction.
 */
@Component
class ReportManifest(
    private val queries: ReportQueries,
    private val files: Files,
    private val transactions: TenantTransactions,
) {
    /** Writes the manifest of [batch] to [out]; returns how many lines it has. */
    fun write(
        batch: ImportBatch,
        out: Path,
    ): Int {
        val tenant = TenantContext.current()
        val stored =
            transactions.readInTenant(tenant) {
                (queries.importedOriginals(batch.id) + queries.storedNotBound(batch.id)).sortedBy { it.path }
            }
        val lines = LinkedHashSet<String>()
        stored.chunked(ImportEndLimits.PAGE).forEach { page ->
            val versions = transactions.readInTenant(tenant) { files.currentVersions(page.map { it.fileId }) }
            page.forEach { original ->
                versions[original.fileId]?.let { lines += "${it.sha256}  ${encode(original.path)}" }
            }
        }
        out.bufferedWriter().use { writer -> lines.forEach { writer.write(it + "\n") } }
        return lines.size
    }

    private fun encode(path: String) = path.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
}
