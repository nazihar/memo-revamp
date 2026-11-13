package com.memo.workflow.internal

import com.memo.audit.AuditLog
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.common.web.refuseIf
import com.memo.jooq.tables.references.WORKFLOW_QUEUE
import com.memo.jooq.tables.references.WORKFLOW_QUEUE_MEMBER
import com.memo.org.OrgDirectory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** No queue by that key in the tenant: 404. */
class QueueNotFoundException : RuntimeException("workflow queue not found")

/** A member of a queue: a person, or whoever holds a position. */
data class QueueMember(
    val userId: UUID? = null,
    val positionId: UUID? = null,
)

data class QueueRow(
    val id: UUID,
    val key: String,
    val name: String,
    val members: List<QueueMember>,
    val version: Long,
)

/**
 * Queues a step can go to (design E04 §4.1, §5): a secretariat, a desk, a committee's members. People are members
 * directly or as holders of a position, resolved when a step activates. Changes are audited. Inside a tenant transaction.
 */
@Component
class Queues(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val directory: OrgDirectory,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    fun all(): List<QueueRow> {
        val members =
            tenantDsl
                .current()
                .selectFrom(WORKFLOW_QUEUE_MEMBER)
                .fetch()
                .groupBy({ it.queueId }, { QueueMember(it.userId, it.positionId) })
        return tenantDsl
            .current()
            .selectFrom(WORKFLOW_QUEUE)
            .orderBy(WORKFLOW_QUEUE.NAME)
            .fetch { QueueRow(it.id, it.key, it.name, members[it.id].orEmpty(), it.version!!) }
    }

    fun find(key: String): QueueRow? = all().firstOrNull { it.key == key }

    /** The people in queue [key] today: members, and the holders of member positions; empty if there is no such queue. */
    fun people(key: String): List<UUID> {
        val queue = find(key) ?: return emptyList()
        val held = directory.holders(queue.members.mapNotNull { it.positionId })
        return (queue.members.mapNotNull { it.userId } + held.values).distinct().sorted()
    }

    fun create(
        key: String,
        name: String,
        members: List<QueueMember>,
    ): QueueRow {
        refuseIf(find(key) != null, "KEY_TAKEN") { "A queue with this key exists." }
        val id = ids.next()
        val now = now()
        tenantDsl
            .current()
            .insertInto(WORKFLOW_QUEUE)
            .set(WORKFLOW_QUEUE.ID, id)
            .set(WORKFLOW_QUEUE.TENANT_ID, tenantDsl.tenant().value)
            .set(WORKFLOW_QUEUE.KEY, key)
            .set(WORKFLOW_QUEUE.NAME, name)
            .set(WORKFLOW_QUEUE.CREATED_AT, now)
            .set(WORKFLOW_QUEUE.UPDATED_AT, now)
            .execute()
        replaceMembers(id, members)
        audit.record("workflow.queue.created", OBJECT_TYPE, id.toString(), after = mapOf("key" to key, "members" to members.size))
        return checkNotNull(find(key))
    }

    /** Renames the queue and replaces its members, if it is still at [version]. */
    fun update(
        key: String,
        version: Long,
        name: String,
        members: List<QueueMember>,
    ): QueueRow {
        val queue = find(key) ?: throw QueueNotFoundException()
        val updated =
            tenantDsl
                .current()
                .update(WORKFLOW_QUEUE)
                .set(WORKFLOW_QUEUE.NAME, name)
                .set(WORKFLOW_QUEUE.VERSION, WORKFLOW_QUEUE.VERSION.plus(1))
                .set(WORKFLOW_QUEUE.UPDATED_AT, now())
                .where(WORKFLOW_QUEUE.ID.eq(queue.id), WORKFLOW_QUEUE.VERSION.eq(version))
                .execute()
        if (updated == 0) throw ConcurrentModificationException("workflow queue ${queue.id}", version)
        replaceMembers(queue.id, members)
        audit.record("workflow.queue.updated", OBJECT_TYPE, queue.id.toString(), after = mapOf("key" to key, "members" to members.size))
        return checkNotNull(find(key))
    }

    private fun replaceMembers(
        queueId: UUID,
        members: List<QueueMember>,
    ) {
        val dsl = tenantDsl.current()
        dsl.deleteFrom(WORKFLOW_QUEUE_MEMBER).where(WORKFLOW_QUEUE_MEMBER.QUEUE_ID.eq(queueId)).execute()
        members.distinct().forEach { member ->
            dsl
                .insertInto(WORKFLOW_QUEUE_MEMBER)
                .set(WORKFLOW_QUEUE_MEMBER.TENANT_ID, tenantDsl.tenant().value)
                .set(WORKFLOW_QUEUE_MEMBER.QUEUE_ID, queueId)
                .set(WORKFLOW_QUEUE_MEMBER.USER_ID, member.userId)
                .set(WORKFLOW_QUEUE_MEMBER.POSITION_ID, member.positionId)
                .execute()
        }
    }

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private companion object {
        const val OBJECT_TYPE = "workflow_queue"
    }
}
