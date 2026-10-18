package com.memo.audit

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** The queryable columns of a stored entry; the verifier checks that they agree with the hashed canonical text. */
data class AuditColumns(
    val tenantId: UUID,
    val occurredAt: Instant,
    val actorType: String,
    val actorId: String?,
    val actingAsId: String?,
    val clientIp: String?,
    val clientUserAgent: String?,
    val correlationId: String?,
    val action: String,
    val objectType: String,
    val objectId: String?,
)

/** A stored audit row as the verifier sees it. */
class StoredAuditEntry(
    val seq: Long,
    val canonical: String,
    val prevHash: ByteArray,
    val hash: ByteArray,
    val columns: AuditColumns,
)

/** A position in a chain: the last verified sequence number and its hash (seq 0 = the genesis hash). */
class ChainPoint(
    val seq: Long,
    val hash: ByteArray,
) {
    companion object {
        fun genesis(chain: UUID) = ChainPoint(0, AuditCanonicalForm.genesis(chain))
    }
}

sealed interface AuditVerification {
    /** Every entry checked; [last] is where the next verification continues. */
    class Ok(
        val last: ChainPoint,
        val checked: Long,
    ) : AuditVerification

    /** The first entry that breaks the chain, and why. */
    data class Broken(
        val seq: Long,
        val reason: String,
    ) : AuditVerification
}

/**
 * Verifies a stretch of one audit chain (ADR-0019), in sequence order, starting after [ChainPoint]. Pure: no database,
 * no clock. Detects modified, deleted, inserted and reordered entries, and columns that disagree with the hashed text.
 * Truncation of the tail is detected by the caller against the chain head and the anchors.
 */
object AuditChainVerifier {
    private val mapper: JsonMapper = JsonMapper.builder().build()

    fun verify(
        chain: UUID,
        from: ChainPoint,
        entries: Sequence<StoredAuditEntry>,
    ): AuditVerification {
        var expectedSeq = from.seq + 1
        var prev = from.hash
        var checked = 0L
        for (entry in entries) {
            val problem =
                when {
                    entry.seq != expectedSeq -> "expected seq $expectedSeq but found ${entry.seq}"
                    !entry.prevHash.contentEquals(prev) -> "previous-hash link broken"
                    !AuditCanonicalForm.hash(prev, entry.canonical).contentEquals(entry.hash) -> "hash does not match the canonical text"
                    else -> columnMismatch(chain, entry)
                }
            if (problem != null) return AuditVerification.Broken(if (entry.seq != expectedSeq) expectedSeq else entry.seq, problem)
            prev = entry.hash
            expectedSeq += 1
            checked += 1
        }
        return AuditVerification.Ok(ChainPoint(expectedSeq - 1, prev), checked)
    }

    private fun columnMismatch(
        chain: UUID,
        entry: StoredAuditEntry,
    ): String? {
        val node = runCatching { mapper.readTree(entry.canonical) }.getOrNull() ?: return "canonical text is not JSON"
        val c = entry.columns
        val checks =
            listOf(
                "version" to (node.long("v") == AuditCanonicalForm.VERSION.toLong()),
                "tenant" to (node.str("tenant") == chain.toString() && c.tenantId == chain),
                "seq" to (node.long("seq") == entry.seq),
                "time" to (node.str("at") == AuditCanonicalForm.timestamp(c.occurredAt)),
                "actor" to (node.at("/actor/type").text() == c.actorType && node.at("/actor/id").text() == c.actorId),
                "acting-as" to (node.at("/actor/actingAs").text() == c.actingAsId),
                "client" to (node.at("/client/ip").text() == c.clientIp && node.at("/client/ua").text() == c.clientUserAgent),
                "correlation" to (node.str("corr") == c.correlationId),
                "action" to (node.str("action") == c.action),
                "object" to (node.at("/object/type").text() == c.objectType && node.at("/object/id").text() == c.objectId),
            )
        return checks.firstOrNull { !it.second }?.let { "column '${it.first}' disagrees with the canonical text" }
    }

    private fun JsonNode.str(field: String): String? = get(field).text()

    private fun JsonNode.long(field: String): Long? = get(field)?.takeIf { it.isIntegralNumber }?.longValue()

    private fun JsonNode?.text(): String? =
        if (this == null || isNull || isMissingNode) {
            null
        } else if (isString) {
            stringValue()
        } else {
            null
        }
}
