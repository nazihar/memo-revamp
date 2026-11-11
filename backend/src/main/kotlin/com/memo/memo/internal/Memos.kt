package com.memo.memo.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.ClassificationLevelRef
import com.memo.common.authz.Clearances
import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.id.IdGenerator
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** What the initiator sets on a memo; a null classification takes the type's default. */
data class MemoFields(
    val subject: String,
    val typeId: UUID,
    val classificationId: UUID?,
)

/**
 * Draft memos (design E01 §3, §5): the initiator creates, edits and discards them; a memo is visible only to its
 * participants who are cleared for its classification, and to everyone else it does not exist (404). The memo audits its
 * own changes; submitting and everything after is the workflow's (T4). Inside a tenant transaction.
 */
@Service
class Memos(
    private val store: MemoStore,
    private val types: MemoTypes,
    private val clearances: Clearances,
    private val policy: Policy,
    private val audit: AuditLog,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    fun create(fields: MemoFields): Memo {
        policy.require(Permission.MEMO_CREATE)
        val user = currentUser()
        val subject = subjectOf(fields.subject)
        val type = types.get(fields.typeId)
        if (!type.active) throw MemoRejectedException(listOf(FieldProblem("typeId", "inactive", "This memo type is no longer used.")))
        val level = classify(type, fields.classificationId)
        val now = now()
        val id = ids.next()
        store.insert(
            NewMemo(id, "M-${now.year}-%05d".format(store.nextNumber(now.year)), type.id, subject, level.id, level.rank, user, null),
            now,
        )
        audit.record(
            "memo.created",
            OBJECT_TYPE,
            id.toString(),
            after = mapOf("typeId" to type.id.toString(), "classificationId" to level.id.toString()),
        )
        return get(id)
    }

    /** The memo, if the actor may see it. */
    fun get(id: UUID): Memo = store.visible(id, currentUser(), policy.clearanceRank()) ?: throw MemoNotFoundException()

    /** Subject and type while the initiator holds the memo; classification only while it is a draft. */
    fun update(
        id: UUID,
        expectedVersion: Long,
        fields: MemoFields,
    ): Memo {
        val memo = own(id)
        refuseIf(!memo.status.editable, "WRONG_STATE") { "The memo can be changed only while it is a draft or returned to you." }
        val type = types.get(fields.typeId)
        val problems = mutableListOf<FieldProblem>()
        if (type.id != memo.typeId && !type.active) problems += FieldProblem("typeId", "inactive", "This memo type is no longer used.")
        val wanted = fields.classificationId ?: memo.classificationId
        if (wanted != memo.classificationId && memo.status != MemoStatus.DRAFT) {
            problems += FieldProblem("classificationId", "locked", "The classification can change only before the memo is submitted.")
        }
        if (problems.isNotEmpty()) throw MemoRejectedException(problems)
        val level = classify(type, wanted)
        val changed =
            memo.copy(subject = subjectOf(fields.subject), typeId = type.id, classificationId = level.id, classificationRank = level.rank)
        val names = changedFields(memo, changed)
        if (names.isEmpty()) return memo
        if (!store.updateFields(changed, expectedVersion, now())) throw ConcurrentModificationException("memo $id", expectedVersion)
        audit.record(
            "memo.updated",
            OBJECT_TYPE,
            id.toString(),
            before = mapOf("classificationId" to memo.classificationId.toString()),
            after = mapOf("fields" to names, "classificationId" to level.id.toString()),
        )
        return get(id)
    }

    /** A draft the initiator no longer wants; it stays, marked discarded (never deleted). */
    fun discard(id: UUID): Memo {
        val memo = own(id)
        refuseIf(memo.status != MemoStatus.DRAFT, "WRONG_STATE") { "Only a draft can be discarded." }
        if (!store.changeStatus(id, memo.version, MemoStatus.DRAFT, MemoStatus.DISCARDED, now())) {
            throw ConcurrentModificationException("memo $id", memo.version)
        }
        audit.record("memo.discarded", OBJECT_TYPE, id.toString())
        return get(id)
    }

    /** The memo, visible to the actor and theirs: others see it (403) or do not (404). */
    fun own(id: UUID): Memo {
        val memo = get(id)
        if (memo.initiatorId != currentUser()) throw ForbiddenException(null)
        return memo
    }

    /**
     * The level for [requested] (or the type's default): it must exist, be at least the type's default and at most the
     * actor's clearance (a memo its own initiator could not see would be lost).
     */
    private fun classify(
        type: MemoType,
        requested: UUID?,
    ): ClassificationLevelRef {
        val floor = type.defaultClassificationId?.let { clearances.level(it) } ?: clearances.lowest()
        val level =
            (requested ?: floor.id).let { clearances.level(it) }
                ?: throw MemoRejectedException(listOf(FieldProblem("classificationId", "unknown", "No such classification level.")))
        val problem =
            when {
                level.rank < floor.rank -> {
                    FieldProblem(
                        "classificationId",
                        "below-type-default",
                        "This memo type needs at least ${floor.name}.",
                    )
                }

                level.rank > policy.clearanceRank() -> {
                    FieldProblem(
                        "classificationId",
                        "above-clearance",
                        "You are not cleared for ${level.name}.",
                    )
                }

                else -> {
                    null
                }
            }
        if (problem != null) throw MemoRejectedException(listOf(problem))
        return level
    }

    private fun subjectOf(value: String): String {
        val subject = value.trim()
        if (subject.isEmpty() || subject.length > MAX_SUBJECT) {
            throw MemoRejectedException(listOf(FieldProblem("subject", "length", "Enter a subject of 1 to $MAX_SUBJECT characters.")))
        }
        return subject
    }

    private fun changedFields(
        before: Memo,
        after: Memo,
    ) = listOfNotNull(
        "subject".takeIf { before.subject != after.subject },
        "typeId".takeIf { before.typeId != after.typeId },
        "classificationId".takeIf { before.classificationId != after.classificationId },
    )

    private fun currentUser(): UUID =
        (ActorContext.current().actor as? Actor.User)?.userId ?: throw IllegalStateException("memos need a signed-in user")

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    companion object {
        const val OBJECT_TYPE = "memo"
        const val MAX_SUBJECT = 300
    }
}
