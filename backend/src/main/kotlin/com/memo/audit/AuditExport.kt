package com.memo.audit

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import org.springframework.stereotype.Component
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Clock
import java.util.HexFormat
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The verifiable audit export (ADR-0033): a ZIP with `events.ndjson` (canonical text and hashes in sequence order),
 * `manifest.json` and `VERIFY.md`, streamed to [OutputStream] without temporary files. The export is recorded in the
 * tenant's chain before any byte is written, so there is no unrecorded export.
 */
@Component
class AuditExport(
    private val query: AuditQuery,
    private val audit: AuditLog,
    private val transactions: TenantTransactions,
    private val clock: Clock,
) {
    fun write(
        filter: AuditFilter,
        exporter: UUID,
        out: OutputStream,
    ) {
        val tenant = TenantContext.current()
        val count = transactions.inTenant(tenant) { query.count(filter).also { record(filter, it) } }
        transactions.readInTenant(tenant) {
            ZipOutputStream(out).use { zip -> writeZip(zip, filter, exporter, count) }
        }
    }

    private fun record(
        filter: AuditFilter,
        count: Int,
    ) = audit.record(
        "audit.exported",
        "audit",
        TenantTransactions.currentTenant().toString(),
        after =
            mapOf(
                "from" to AuditCanonicalForm.timestamp(filter.from),
                "to" to AuditCanonicalForm.timestamp(filter.to),
                "action" to filter.actionPrefix,
                "actor" to filter.actorId,
                "objectType" to filter.objectType,
                "objectId" to filter.objectId,
                "count" to count.toLong(),
                "continuous" to filter.timeRangeOnly,
            ),
    )

    private fun writeZip(
        zip: ZipOutputStream,
        filter: AuditFilter,
        exporter: UUID,
        matching: Int,
    ) {
        val hex = HexFormat.of()
        val digest = MessageDigest.getInstance("SHA-256")
        var first: AuditLine? = null
        var last: AuditLine? = null
        var lines = 0L
        zip.putNextEntry(ZipEntry(EVENTS))
        val events = DigestOutputStream(NonClosing(zip), digest)
        query.forEachLine(filter) { line ->
            if (first == null) first = line
            last = line
            lines++
            val json =
                AuditCanonicalForm
                    .json(
                        mapOf("seq" to line.seq, "prevHash" to hex.formatHex(line.prevHash), "hash" to hex.formatHex(line.hash)),
                    ).dropLast(1) + ",\"canonical\":" + AuditCanonicalForm.json(line.canonical) + "}\n"
            events.write(json.toByteArray(Charsets.UTF_8))
        }
        events.flush()
        zip.closeEntry()
        val anchors = first?.let { f -> query.anchors(f.seq, last!!.seq) }.orEmpty()
        val manifest =
            mapOf(
                "format" to "memo-audit-export",
                "version" to 1L,
                "tenant" to TenantTransactions.currentTenant().toString(),
                "genesis" to hex.formatHex(AuditCanonicalForm.genesis(TenantTransactions.currentTenant().value)),
                "filter" to
                    mapOf(
                        "from" to AuditCanonicalForm.timestamp(filter.from),
                        "to" to AuditCanonicalForm.timestamp(filter.to),
                        "action" to filter.actionPrefix,
                        "actor" to filter.actorId,
                        "objectType" to filter.objectType,
                        "objectId" to filter.objectId,
                    ),
                "continuous" to filter.timeRangeOnly,
                "count" to lines,
                "matchingWhenRecorded" to matching.toLong(),
                "firstSeq" to first?.seq,
                "lastSeq" to last?.seq,
                "firstPrevHash" to first?.let { hex.formatHex(it.prevHash) },
                "lastHash" to last?.let { hex.formatHex(it.hash) },
                "eventsSha256" to hex.formatHex(digest.digest()),
                "anchors" to anchors.map { mapOf("seq" to it.seq, "hash" to hex.formatHex(it.hash), "objectKey" to it.objectKey) },
                "exportedBy" to exporter.toString(),
                "exportedAt" to AuditCanonicalForm.timestamp(clock.instant()),
            )
        zip.putNextEntry(ZipEntry(MANIFEST))
        zip.write(AuditCanonicalForm.json(manifest).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.putNextEntry(ZipEntry(VERIFY))
        zip.write(VERIFY_TEXT.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /** Lets the digest stream flush into the open ZIP entry without closing the ZIP. */
    private class NonClosing(
        private val target: OutputStream,
    ) : OutputStream() {
        override fun write(b: Int) = target.write(b)

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) = target.write(b, off, len)

        override fun flush() = target.flush()

        override fun close() = flush()
    }

    companion object {
        const val EVENTS = "events.ndjson"
        const val MANIFEST = "manifest.json"
        const val VERIFY = "VERIFY.md"

        private val VERIFY_TEXT =
            """
            # Verifying this audit export

            `events.ndjson` has one JSON object per line, in sequence order: `seq`, `prevHash`, `hash` (hex) and `canonical`
            (the exact stored text of the audit entry).

            1. Check that the SHA-256 of `events.ndjson` equals `eventsSha256` in `manifest.json`.
            2. For every line, compute `SHA-256(bytes(prevHash) || UTF-8(canonical))`; it must equal `hash`.
            3. If `continuous` is true, every line's `prevHash` must equal the previous line's `hash`, the first line's `prevHash`
               must equal `firstPrevHash`, and the last line's `hash` must equal `lastHash`. For the first entry of the chain,
               `prevHash` equals `genesis`, which is `SHA-256(UTF-8("memo-audit-genesis:" + tenant))`.
            4. For each entry in `anchors`, read the object `objectKey` from the organisation's object-locked records bucket;
               its hash must equal the line with the same `seq`. This shows the entries existed unchanged when anchored.

            A filtered export (`continuous` false) proves each entry's own hash but cannot prove that no entry is missing.
            """.trimIndent() + "\n"
    }
}
