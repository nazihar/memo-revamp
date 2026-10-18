package com.memo.audit.internal

import com.memo.audit.AuditColumns
import com.memo.audit.ChainPoint
import com.memo.audit.StoredAuditEntry
import com.memo.common.id.IdGenerator
import com.memo.jooq.tables.records.AuditEventRecord
import com.memo.jooq.tables.references.AUDIT_ANCHOR
import com.memo.jooq.tables.references.AUDIT_CHAIN_HEAD
import com.memo.jooq.tables.references.AUDIT_EVENT
import com.memo.jooq.tables.references.AUDIT_VERIFICATION
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Verification checkpoint of one chain. */
internal data class Checkpoint(
    val point: ChainPoint,
    val broken: Boolean,
)

/**
 * Reads and maintenance writes on the audit tables for verification and anchoring. Every call runs inside a transaction
 * that the caller opened in the chain's scope (tenant, or platform for the platform chain), so RLS confines it.
 */
@Component
internal class AuditStore(
    private val dsl: DSLContext,
    private val ids: IdGenerator,
) {
    fun head(chain: UUID): ChainPoint? =
        dsl
            .select(AUDIT_CHAIN_HEAD.LAST_SEQ, AUDIT_CHAIN_HEAD.LAST_HASH)
            .from(AUDIT_CHAIN_HEAD)
            .where(AUDIT_CHAIN_HEAD.TENANT_ID.eq(chain))
            .fetchOne { ChainPoint(it.value1()!!, it.value2()!!) }

    /** Entries with `afterSeq < seq <= upToSeq`, in sequence order. */
    fun page(
        chain: UUID,
        afterSeq: Long,
        upToSeq: Long,
        limit: Int,
    ): List<StoredAuditEntry> =
        dsl
            .selectFrom(AUDIT_EVENT)
            .where(AUDIT_EVENT.TENANT_ID.eq(chain))
            .and(AUDIT_EVENT.SEQ.gt(afterSeq))
            .and(AUDIT_EVENT.SEQ.le(upToSeq))
            .orderBy(AUDIT_EVENT.SEQ)
            .limit(limit)
            .fetch { it.toStored() }

    fun hashAt(
        chain: UUID,
        seq: Long,
    ): ByteArray? =
        dsl
            .select(AUDIT_EVENT.HASH)
            .from(AUDIT_EVENT)
            .where(AUDIT_EVENT.TENANT_ID.eq(chain))
            .and(AUDIT_EVENT.SEQ.eq(seq))
            .limit(1)
            .fetchOne(AUDIT_EVENT.HASH)

    fun checkpoint(chain: UUID): Checkpoint? =
        dsl
            .selectFrom(AUDIT_VERIFICATION)
            .where(AUDIT_VERIFICATION.TENANT_ID.eq(chain))
            .fetchOne { Checkpoint(ChainPoint(it.verifiedSeq, it.verifiedHash), it.status == "BROKEN") }

    fun saveOk(
        chain: UUID,
        point: ChainPoint,
        at: Instant,
    ) {
        val now = at.atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(AUDIT_VERIFICATION)
            .set(AUDIT_VERIFICATION.TENANT_ID, chain)
            .set(AUDIT_VERIFICATION.VERIFIED_SEQ, point.seq)
            .set(AUDIT_VERIFICATION.VERIFIED_HASH, point.hash)
            .set(AUDIT_VERIFICATION.VERIFIED_AT, now)
            .set(AUDIT_VERIFICATION.STATUS, "OK")
            .onConflict(AUDIT_VERIFICATION.TENANT_ID)
            .doUpdate()
            .set(AUDIT_VERIFICATION.VERIFIED_SEQ, point.seq)
            .set(AUDIT_VERIFICATION.VERIFIED_HASH, point.hash)
            .set(AUDIT_VERIFICATION.VERIFIED_AT, now)
            // A broken chain stays broken until a person investigates (E07); a later clean run never clears it.
            .where(AUDIT_VERIFICATION.STATUS.eq("OK"))
            .execute()
    }

    fun saveBroken(
        chain: UUID,
        lastGood: ChainPoint,
        brokenAt: Long,
        reason: String,
        at: Instant,
    ) {
        val now = at.atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(AUDIT_VERIFICATION)
            .set(AUDIT_VERIFICATION.TENANT_ID, chain)
            .set(AUDIT_VERIFICATION.VERIFIED_SEQ, lastGood.seq)
            .set(AUDIT_VERIFICATION.VERIFIED_HASH, lastGood.hash)
            .set(AUDIT_VERIFICATION.VERIFIED_AT, now)
            .set(AUDIT_VERIFICATION.STATUS, "BROKEN")
            .set(AUDIT_VERIFICATION.BROKEN_AT_SEQ, brokenAt)
            .set(AUDIT_VERIFICATION.REASON, reason.take(MAX_REASON))
            .onConflict(AUDIT_VERIFICATION.TENANT_ID)
            .doUpdate()
            .set(AUDIT_VERIFICATION.VERIFIED_AT, now)
            .set(AUDIT_VERIFICATION.STATUS, "BROKEN")
            .set(AUDIT_VERIFICATION.BROKEN_AT_SEQ, brokenAt)
            .set(AUDIT_VERIFICATION.REASON, reason.take(MAX_REASON))
            .execute()
    }

    fun recordAnchor(
        chain: UUID,
        point: ChainPoint,
        objectKey: String,
        retainUntil: Instant,
        at: Instant,
    ) {
        dsl
            .insertInto(AUDIT_ANCHOR)
            .set(AUDIT_ANCHOR.ID, ids.next())
            .set(AUDIT_ANCHOR.TENANT_ID, chain)
            .set(AUDIT_ANCHOR.SEQ, point.seq)
            .set(AUDIT_ANCHOR.HASH, point.hash)
            .set(AUDIT_ANCHOR.OBJECT_KEY, objectKey)
            .set(AUDIT_ANCHOR.RETAIN_UNTIL, retainUntil.atOffset(ZoneOffset.UTC))
            .set(AUDIT_ANCHOR.ANCHORED_AT, at.atOffset(ZoneOffset.UTC))
            .onConflict(AUDIT_ANCHOR.TENANT_ID, AUDIT_ANCHOR.SEQ)
            .doNothing()
            .execute()
    }

    private fun AuditEventRecord.toStored() =
        StoredAuditEntry(
            seq = seq,
            canonical = canonical,
            prevHash = prevHash,
            hash = hash,
            columns =
                AuditColumns(
                    tenantId = tenantId,
                    occurredAt = occurredAt.toInstant(),
                    actorType = actorType,
                    actorId = actorId,
                    actingAsId = actingAsId,
                    clientIp = clientIp,
                    clientUserAgent = clientUserAgent,
                    correlationId = correlationId,
                    action = action,
                    objectType = objectType,
                    objectId = objectId,
                ),
        )

    private companion object {
        const val MAX_REASON = 500
    }
}
