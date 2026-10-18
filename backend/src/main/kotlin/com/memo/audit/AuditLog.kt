package com.memo.audit

import java.time.Instant
import java.util.UUID

/**
 * Records audit entries in the current business transaction (ADR-0019). Entries are buffered and written, hash-chained,
 * just before commit; if the transaction rolls back, nothing is written, and if writing fails, the commit fails.
 *
 * Call it from module services for every state change. [before] and [after] hold ids and changed fields only (strings,
 * integers, booleans, nested maps or lists of those); keys that look like secrets are redacted.
 */
interface AuditLog {
    fun record(
        action: String,
        objectType: String,
        objectId: String?,
        before: Map<String, Any?>? = null,
        after: Map<String, Any?>? = null,
    )
}

/** Thrown when an audit entry is recorded where it cannot be written in the same commit as the change. */
class AuditContextException(
    message: String,
) : IllegalStateException(message)

/** The chain id of platform operations (E07); visible only in platform scope. */
object PlatformChain {
    val ID: UUID = UUID(0, 0)
}

/** One audit entry as hashed: exactly the fields of its canonical form (ADR-0019). */
data class AuditEntry(
    val tenantId: UUID,
    val seq: Long,
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
    val before: Map<String, Any?>?,
    val after: Map<String, Any?>?,
)
