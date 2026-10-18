package com.memo.audit.internal

import com.memo.audit.AuditCanonicalForm
import com.memo.audit.AuditChainVerifier
import com.memo.audit.AuditLog
import com.memo.audit.AuditVerification
import com.memo.audit.ChainPoint
import com.memo.audit.PlatformChain
import com.memo.common.storage.RecordStore
import com.memo.common.storage.RetentionMode
import com.memo.common.tenant.PlatformTransactions
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.HexFormat
import java.util.UUID

/** Runs a block in the scope that can see [chain]: its tenant, or platform scope for the platform chain. */
@Component
internal class ChainScope(
    private val tenants: TenantTransactions,
    private val platform: PlatformTransactions,
) {
    fun <T> read(
        chain: UUID,
        block: () -> T,
    ): T = if (chain == PlatformChain.ID) platform.read(block) else tenants.readInTenant(TenantId(chain), block)

    fun <T> write(
        chain: UUID,
        block: () -> T,
    ): T = if (chain == PlatformChain.ID) platform.write(block) else tenants.inTenant(TenantId(chain), block)
}

/** Where anchors of a chain live. Keys sort newest-first, so the latest anchor is the first key under the prefix. */
internal object AnchorKeys {
    fun prefix(chain: UUID) = "audit-anchors/$chain/"

    fun key(
        chain: UUID,
        seq: Long,
    ) = prefix(chain) + "%019d".format(Long.MAX_VALUE - seq)

    fun seqOf(key: String): Long = Long.MAX_VALUE - key.substringAfterLast('/').toLong()
}

/**
 * Verifies chains and writes anchors (ADR-0019). Verification is incremental from the chain's checkpoint, or full from
 * genesis; it checks every entry, the chain head and the latest anchor in the object-locked bucket. A failure is logged at
 * ERROR, counted, stored as BROKEN (never cleared automatically) and recorded in the platform chain.
 */
@Component
internal class AuditIntegrity(
    private val store: AuditStore,
    private val scope: ChainScope,
    private val records: RecordStore,
    private val platform: PlatformTransactions,
    private val audit: AuditLog,
    private val clock: Clock,
    private val meters: MeterRegistry,
    @param:Value("\${memo.audit.anchor-retention:P3650D}") private val anchorRetention: Duration,
    @param:Value("\${memo.audit.anchor-retention-mode:COMPLIANCE}") private val anchorMode: RetentionMode,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun verify(
        chain: UUID,
        full: Boolean,
    ): AuditVerification {
        val (checkpoint, head) = scope.read(chain) { store.checkpoint(chain) to store.head(chain) }
        val start = if (full || checkpoint == null) ChainPoint.genesis(chain) else checkpoint.point
        return when {
            checkpoint?.broken == true && !full -> AuditVerification.Broken(checkpoint.point.seq + 1, "already broken")
            head == null -> AuditVerification.Ok(start, 0)
            else -> verifyUpTo(chain, start, head)
        }
    }

    private fun verifyUpTo(
        chain: UUID,
        start: ChainPoint,
        head: ChainPoint,
    ): AuditVerification {
        val scan = scan(chain, start, head)
        val problem = scan.problem ?: headMismatch(scan.point, head) ?: anchorMismatch(chain, head)
        if (problem != null) return broken(chain, scan.point, problem)
        scope.write(chain) { store.saveOk(chain, scan.point, clock.instant()) }
        return AuditVerification.Ok(scan.point, scan.checked)
    }

    /** Reads and verifies the chain page by page from [start] up to [head]; stops at the first problem. */
    private fun scan(
        chain: UUID,
        start: ChainPoint,
        head: ChainPoint,
    ): Scan {
        var point = start
        var checked = 0L
        var problem: Problem? = null
        while (problem == null && point.seq < head.seq) {
            val page = scope.read(chain) { store.page(chain, point.seq, head.seq, PAGE_SIZE) }
            if (page.isEmpty()) break
            when (val result = AuditChainVerifier.verify(chain, point, page.asSequence())) {
                is AuditVerification.Broken -> {
                    problem = Problem(result.seq, result.reason)
                }

                is AuditVerification.Ok -> {
                    point = result.last
                    checked += result.checked
                }
            }
        }
        return Scan(point, checked, problem)
    }

    private fun headMismatch(
        point: ChainPoint,
        head: ChainPoint,
    ): Problem? =
        if (point.seq != head.seq || !point.hash.contentEquals(head.hash)) {
            Problem(point.seq + 1, "chain head (seq ${head.seq}) does not match the stored entries")
        } else {
            null
        }

    /** Writes an anchor for the chain's head unless the latest anchor already covers it. Returns the anchored point. */
    fun anchor(chain: UUID): ChainPoint? {
        if (!records.enabled) {
            log.warn("Object storage is not configured: audit chain heads are not anchored")
            return null
        }
        val head = scope.read(chain) { store.head(chain) }
        val latest = records.firstKey(AnchorKeys.prefix(chain))?.let(AnchorKeys::seqOf)
        return if (head != null && (latest == null || latest < head.seq)) writeAnchor(chain, head) else null
    }

    private fun writeAnchor(
        chain: UUID,
        head: ChainPoint,
    ): ChainPoint {
        val now = clock.instant()
        val retainUntil = now.plus(anchorRetention)
        val key = AnchorKeys.key(chain, head.seq)
        val body =
            AuditCanonicalForm.json(
                mapOf(
                    "chain" to chain.toString(),
                    "seq" to head.seq,
                    "hash" to HexFormat.of().formatHex(head.hash),
                    "anchoredAt" to AuditCanonicalForm.timestamp(now),
                ),
            )
        records.putLocked(key, body.toByteArray(Charsets.UTF_8), "application/json", retainUntil, anchorMode)
        scope.write(chain) { store.recordAnchor(chain, head, key, retainUntil, now) }
        return head
    }

    /** Compares the latest anchor in the bucket (the authority) with the stored entry at that sequence number. */
    private fun anchorMismatch(
        chain: UUID,
        head: ChainPoint,
    ): Problem? {
        val key = if (records.enabled) records.firstKey(AnchorKeys.prefix(chain)) else null
        return key?.let { anchorProblem(chain, head, it) }
    }

    private fun anchorProblem(
        chain: UUID,
        head: ChainPoint,
        key: String,
    ): Problem? {
        val anchoredSeq = AnchorKeys.seqOf(key)
        val body = records.get(key)?.toString(Charsets.UTF_8)
        val anchoredHash = body?.let { ANCHORED_HASH.find(it)?.groupValues?.get(1) }
        val withinHead = anchoredSeq <= head.seq
        val stored = if (withinHead) scope.read(chain) { store.hashAt(chain, anchoredSeq) }?.let(HexFormat.of()::formatHex) else null
        return when {
            body == null -> Problem(anchoredSeq, "anchor object $key is missing")
            anchoredSeq > head.seq -> Problem(anchoredSeq, "anchored seq $anchoredSeq is beyond the chain head ${head.seq}")
            stored == null || stored != anchoredHash -> Problem(anchoredSeq, "entry $anchoredSeq does not match its anchor")
            else -> null
        }
    }

    private fun broken(
        chain: UUID,
        lastGood: ChainPoint,
        problem: Problem,
    ): AuditVerification.Broken {
        log.error("Audit chain {} is broken at seq {}: {}", chain, problem.seq, problem.reason)
        meters.counter(BROKEN_METRIC).increment()
        scope.write(chain) { store.saveBroken(chain, lastGood, problem.seq, problem.reason, clock.instant()) }
        platform.write {
            audit.record(
                "audit.chain.verification_failed",
                "audit.chain",
                chain.toString(),
                after = mapOf("seq" to problem.seq, "reason" to problem.reason),
            )
        }
        return AuditVerification.Broken(problem.seq, problem.reason)
    }

    private data class Problem(
        val seq: Long,
        val reason: String,
    )

    private class Scan(
        val point: ChainPoint,
        val checked: Long,
        val problem: Problem?,
    )

    companion object {
        const val BROKEN_METRIC = "memo.audit.chain.broken"
        private const val PAGE_SIZE = 5_000
        private val ANCHORED_HASH = Regex("\"hash\":\"([0-9a-f]{64})\"")
    }
}
