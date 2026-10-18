package com.memo.audit.internal

import com.memo.audit.AuditCanonicalForm
import com.memo.audit.AuditContextException
import com.memo.audit.AuditEntry
import com.memo.audit.AuditLog
import com.memo.audit.PlatformChain
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantAwareTransactionManager
import com.memo.common.web.CorrelationIdFilter
import com.memo.jooq.tables.records.AuditEventRecord
import com.memo.jooq.tables.references.AUDIT_CHAIN_HEAD
import com.memo.jooq.tables.references.AUDIT_EVENT
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/** An entry recorded in the current transaction, waiting for the pre-commit flush. */
internal data class PendingEntry(
    val action: String,
    val objectType: String,
    val objectId: String?,
    val before: Map<String, Any?>?,
    val after: Map<String, Any?>?,
    val actor: ActorScope,
    val correlationId: String?,
)

/**
 * [AuditLog] that buffers entries on the transaction and writes them in `beforeCommit` (ADR-0019): lock the chain head,
 * number and hash the entries, insert them, move the head. The head lock is the last lock the transaction takes and is
 * held only until commit.
 */
@Component
internal class TransactionalAuditLog(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val ids: IdGenerator,
    meters: MeterRegistry,
) : AuditLog {
    private val flushTimer: Timer = Timer.builder("memo.audit.flush").publishPercentiles(0.5, 0.95, 0.99).register(meters)

    override fun record(
        action: String,
        objectType: String,
        objectId: String?,
        before: Map<String, Any?>?,
        after: Map<String, Any?>?,
    ) {
        require(ACTION.matches(action) && action.length <= MAX_NAME) { "invalid audit action '$action'" }
        require(OBJECT_TYPE.matches(objectType) && objectType.length <= MAX_NAME) { "invalid audit object type '$objectType'" }
        val chain = chainOfCurrentTransaction()
        val actor = ActorContext.currentOrNull() ?: throw AuditContextException("No actor bound: every entry point must bind one")
        val buffer = currentBuffer(chain)
        check(!buffer.flushed) { "Audit entry recorded after the pre-commit flush" }
        buffer.entries +=
            PendingEntry(
                action,
                objectType,
                objectId,
                AuditRedaction.redact(before),
                AuditRedaction.redact(after),
                actor,
                MDC.get(CorrelationIdFilter.MDC_KEY),
            )
    }

    /** The chain of the current read-write transaction: its tenant, or the platform chain in platform scope. */
    private fun chainOfCurrentTransaction(): UUID {
        val readWrite =
            TransactionSynchronizationManager.isActualTransactionActive() &&
                !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        val scope = TenantAwareTransactionManager.currentScope()
        val chain = scope?.tenant?.value ?: PlatformChain.ID.takeIf { scope?.platform == true }
        if (!readWrite || chain == null) {
            throw AuditContextException("Audit entries must be recorded in a read-write transaction with a tenant or platform scope")
        }
        return chain
    }

    private fun currentBuffer(chain: UUID): Buffer {
        val existing = TransactionSynchronizationManager.getResource(BUFFER_KEY) as Buffer?
        if (existing != null) {
            check(existing.chain == chain) { "One transaction cannot write to two audit chains" }
            return existing
        }
        val buffer = Buffer(chain)
        TransactionSynchronizationManager.bindResource(BUFFER_KEY, buffer)
        TransactionSynchronizationManager.registerSynchronization(FlushOnCommit(buffer))
        return buffer
    }

    private inner class FlushOnCommit(
        private val buffer: Buffer,
    ) : TransactionSynchronization {
        override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

        override fun beforeCommit(readOnly: Boolean) {
            flushTimer.record(Runnable { flush(buffer) })
        }

        override fun afterCompletion(status: Int) {
            TransactionSynchronizationManager.unbindResourceIfPossible(BUFFER_KEY)
        }
    }

    private fun flush(buffer: Buffer) {
        buffer.flushed = true
        if (buffer.entries.isEmpty()) return
        val chain = buffer.chain
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        // Round trip 1: lock and read the head (created on a chain's first entry only).
        val head = lockHead(chain) ?: createHead(chain, now).let { lockHead(chain)!! }
        var seq = head.first
        var prev = head.second
        val rows =
            buffer.entries.map { pending ->
                seq += 1
                val entry = pending.toEntry(chain, seq, now)
                val canonical = AuditCanonicalForm.encode(entry)
                val hash = AuditCanonicalForm.hash(prev, canonical)
                entry.toRecord(canonical, prev, hash).also { prev = hash }
            }
        // Round trip 2: insert the entries and move the head in one statement (data-modifying CTE).
        val inserted = DSL.name("inserted")
        val moved =
            dsl
                .with(
                    inserted.`as`(
                        dsl
                            .insertInto(AUDIT_EVENT)
                            .columns(*AUDIT_EVENT.fields())
                            .valuesOfRecords(rows)
                            .returning(AUDIT_EVENT.ID),
                    ),
                ).update(AUDIT_CHAIN_HEAD)
                .set(AUDIT_CHAIN_HEAD.LAST_SEQ, seq)
                .set(AUDIT_CHAIN_HEAD.LAST_HASH, prev)
                .set(AUDIT_CHAIN_HEAD.UPDATED_AT, now.atOffset(ZoneOffset.UTC))
                .where(AUDIT_CHAIN_HEAD.TENANT_ID.eq(chain))
                .execute()
        check(moved == 1) { "audit chain head of $chain was not moved" }
    }

    private fun lockHead(chain: UUID): Pair<Long, ByteArray>? =
        dsl
            .select(AUDIT_CHAIN_HEAD.LAST_SEQ, AUDIT_CHAIN_HEAD.LAST_HASH)
            .from(AUDIT_CHAIN_HEAD)
            .where(AUDIT_CHAIN_HEAD.TENANT_ID.eq(chain))
            .forUpdate()
            .fetchOne { it.value1()!! to it.value2()!! }

    private fun createHead(
        chain: UUID,
        now: java.time.Instant,
    ) {
        dsl
            .insertInto(AUDIT_CHAIN_HEAD)
            .set(AUDIT_CHAIN_HEAD.TENANT_ID, chain)
            .set(AUDIT_CHAIN_HEAD.LAST_SEQ, 0L)
            .set(AUDIT_CHAIN_HEAD.LAST_HASH, AuditCanonicalForm.genesis(chain))
            .set(AUDIT_CHAIN_HEAD.UPDATED_AT, now.atOffset(ZoneOffset.UTC))
            .onConflict(AUDIT_CHAIN_HEAD.TENANT_ID)
            .doNothing()
            .execute()
    }

    private fun PendingEntry.toEntry(
        chain: UUID,
        seq: Long,
        now: java.time.Instant,
    ) = AuditEntry(
        tenantId = chain,
        seq = seq,
        occurredAt = now,
        actorType = actor.actor.type,
        actorId = actor.actor.id,
        actingAsId = (actor.actor as? Actor.User)?.actingAs?.toString(),
        clientIp = actor.client?.ip,
        clientUserAgent = actor.client?.userAgent,
        correlationId = correlationId,
        action = action,
        objectType = objectType,
        objectId = objectId,
        before = before,
        after = after,
    )

    private fun AuditEntry.toRecord(
        canonical: String,
        prev: ByteArray,
        hash: ByteArray,
    ): AuditEventRecord =
        dsl.newRecord(AUDIT_EVENT).apply {
            id = ids.next()
            tenantId = this@toRecord.tenantId
            seq = this@toRecord.seq
            occurredAt = this@toRecord.occurredAt.atOffset(ZoneOffset.UTC)
            actorType = this@toRecord.actorType
            actorId = this@toRecord.actorId
            actingAsId = this@toRecord.actingAsId
            clientIp = this@toRecord.clientIp
            clientUserAgent = this@toRecord.clientUserAgent
            correlationId = this@toRecord.correlationId
            action = this@toRecord.action
            objectType = this@toRecord.objectType
            objectId = this@toRecord.objectId
            this.canonical = canonical
            prevHash = prev
            this.hash = hash
        }

    private class Buffer(
        val chain: UUID,
    ) {
        val entries = mutableListOf<PendingEntry>()
        var flushed = false
    }

    private companion object {
        val BUFFER_KEY = Any()
        val ACTION = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")
        val OBJECT_TYPE = Regex("^[a-z][a-z0-9_.]*$")
        const val MAX_NAME = 100
    }
}

/** Keeps secrets out of audit before/after values (threat model I5): values under secret-looking keys are replaced. */
internal object AuditRedaction {
    private val SECRET_KEY = Regex("(?i).*(password|secret|token|credential|otp|recovery|private).*")
    const val REDACTED = "[redacted]"

    fun redact(values: Map<String, Any?>?): Map<String, Any?>? =
        values?.mapValues { (k, v) -> if (SECRET_KEY.matches(k)) REDACTED else redactValue(v) }

    @Suppress("UNCHECKED_CAST")
    private fun redactValue(value: Any?): Any? =
        when (value) {
            is Map<*, *> -> redact(value as Map<String, Any?>)
            is List<*> -> value.map { redactValue(it) }
            else -> value
        }
}
