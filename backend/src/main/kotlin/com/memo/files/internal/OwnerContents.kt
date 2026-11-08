package com.memo.files.internal

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileContents
import com.memo.files.FileVersionInfo
import org.springframework.stereotype.Component
import java.io.OutputStream
import java.util.UUID

/** [FileContents] over the download path (ADR-0029): metadata in a short transaction, streaming outside it. */
@Component
internal class OwnerContents(
    private val store: FileStore,
    private val purposes: Purposes,
    private val downloads: Downloads,
    private val transactions: TenantTransactions,
) : FileContents {
    override fun current(
        fileId: UUID,
        purpose: String,
    ): FileVersionInfo? =
        transactions.readInTenant(TenantContext.current()) {
            store
                .info(fileId)
                ?.takeIf { it.purpose == purpose }
                ?.currentVersionId
                ?.let(store::version)
        }

    override fun write(
        version: FileVersionInfo,
        out: OutputStream,
    ) {
        val readable =
            transactions.readInTenant(TenantContext.current()) {
                val info = requireNotNull(store.info(version.fileId)) { "unknown file" }
                Readable(
                    version,
                    requireNotNull(purposes[info.purpose]) { "unknown purpose" },
                    requireNotNull(store.storedObject(version.id)),
                )
            }
        downloads.stream(readable, 0, version.sizeBytes, out)
    }
}
