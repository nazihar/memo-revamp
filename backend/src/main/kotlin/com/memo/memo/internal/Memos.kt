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
import com.memo.org.OrgDirectory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * What the initiator sets on a memo; a null classification takes the type's default. [metadata] holds the values of the
 * type's fields (ADR-0043); null on an edit leaves them unchanged.
 */
data class MemoFields(
    val subject: String,
    val typeId: UUID,
    val classificationId: UUID?,
    val metadata: Map<String, Any?>? = null,
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
    private val metadata: MemoMetadata,
    private val directory: OrgDirectory,
    private val access: MemoTypeAccess,
) {
    fun create(fields: MemoFields): Memo {
        policy.require(Permission.MEMO_CREATE)
        val user = currentUser()
        val subject = subjectOf(fields.subject)
        val type = types.get(fields.typeId)
        typeProblem(type, user)?.let { throw MemoRejectedException(listOf(it)) }
        val version = checkNotNull(types.published(type.id))
        val level = classify(type, fields.classificationId)
        val checked = metadata.check(version.settings.fields, fields.metadata.orEmpty(), requireAll = false)
        if (checked.problems.isNotEmpty()) throw MemoRejectedException(checked.problems)
        val now = now()
        val id = ids.next()
        // The unit the memo comes from: the initiator's position today (Phase 1b adds a choice between assignments).
        val origin = directory.positionsOf(listOf(user))[user]?.unitId
        store.insert(
            NewMemo(
                id,
                type.id,
                subject,
                level.id,
                level.rank,
                user,
                origin,
                checked.values,
            ),
            now,
        )
        audit.record(
            "memo.created",
            OBJECT_TYPE,
            id.toString(),
            after =
                mapOf(
                    "typeId" to type.id.toString(),
                    "classificationId" to level.id.toString(),
                    "metadata" to checked.values.keys.sorted(),
                ),
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
        val draft = memo.status == MemoStatus.DRAFT
        val typeChanged = type.id != memo.typeId
        val wanted = fields.classificationId ?: memo.classificationId
        val values = fields.metadata
        val problems =
            if (draft) {
                listOfNotNull(typeProblem(type, currentUser()).takeIf { typeChanged })
            } else {
                lockedProblems(memo, typeChanged, wanted, values)
            }
        if (problems.isNotEmpty()) throw MemoRejectedException(problems)
        val level = classify(type, wanted)
        val metadata = if (draft) draftMetadata(memo, type.id, typeChanged, values) else memo.metadata
        val changed =
            memo.copy(
                subject = subjectOf(fields.subject),
                typeId = type.id,
                classificationId = level.id,
                classificationRank = level.rank,
                metadata = metadata,
            )
        val names = changedFields(memo, changed)
        if (names.isEmpty()) return memo
        if (!store.updateFields(changed, expectedVersion, now())) throw ConcurrentModificationException("memo $id", expectedVersion)
        audit.record(
            "memo.updated",
            OBJECT_TYPE,
            id.toString(),
            before = mapOf("classificationId" to memo.classificationId.toString()),
            after = mapOf("fields" to names, "classificationId" to level.id.toString(), "metadata" to changedKeys(memo, changed)),
        )
        return get(id)
    }

    /**
     * A draft's metadata after an edit: [values] checked against the type's published fields (types and rules; required
     * only at submit). Values the draft holds for fields its type no longer has stay until submit (ADR-0043), unless the
     * type itself changed; null [values] leave the metadata as it is (cleared when the type changed).
     */
    private fun draftMetadata(
        memo: Memo,
        typeId: UUID,
        typeChanged: Boolean,
        values: Map<String, Any?>?,
    ): Map<String, Any> {
        if (values == null) return if (typeChanged) emptyMap() else memo.metadata
        val fields = checkNotNull(types.published(typeId)).settings.fields
        val keys = fields.map { it.key }.toSet()
        // A stale value the client sends back unchanged is not an unknown field.
        val sent = values.filterKeys { it in keys || memo.metadata[it] != values[it] }
        val checked = metadata.check(fields, sent, requireAll = false)
        if (checked.problems.isNotEmpty()) throw MemoRejectedException(checked.problems)
        val stale = if (typeChanged) emptyMap() else memo.metadata.filterKeys { it !in keys }
        return stale + checked.values
    }

    /** Why [user] cannot raise a memo of [type] now, if they cannot: it is off or unpublished, or outside their scope. */
    private fun typeProblem(
        type: MemoType,
        user: UUID,
    ): FieldProblem? =
        when {
            !type.active || types.published(type.id) == null -> FieldProblem("typeId", "inactive", "This memo type is no longer used.")
            !access.mayRaise(type.id, user) -> FieldProblem("typeId", "NOT_ALLOWED_TO_RAISE", "You cannot raise memos of this type.")
            else -> null
        }

    /** After submit, type, classification and details are fixed (ADR-0043): what an edit of a returned memo may not change. */
    private fun lockedProblems(
        memo: Memo,
        typeChanged: Boolean,
        classification: UUID,
        values: Map<String, Any?>?,
    ): List<FieldProblem> =
        listOfNotNull(
            FieldProblem("typeId", "locked", "The memo type can change only before the memo is submitted.").takeIf { typeChanged },
            FieldProblem("classificationId", "locked", "The classification can change only before the memo is submitted.").takeIf {
                classification != memo.classificationId
            },
            FieldProblem("metadata", "locked", "The details can change only before the memo is submitted.").takeIf {
                values != null && values != memo.metadata
            },
        )

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

    private fun currentUser(): UUID =
        (ActorContext.current().actor as? Actor.User)?.userId ?: throw IllegalStateException("memos need a signed-in user")

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    companion object {
        const val OBJECT_TYPE = "memo"
        const val MAX_SUBJECT = 300
    }
}

private fun changedKeys(
    before: Memo,
    after: Memo,
): List<String> = (before.metadata.keys + after.metadata.keys).filter { before.metadata[it] != after.metadata[it] }.sorted()

private fun subjectOf(value: String): String {
    val subject = value.trim()
    if (subject.isEmpty() || subject.length > Memos.MAX_SUBJECT) {
        throw MemoRejectedException(listOf(FieldProblem("subject", "length", "Enter a subject of 1 to ${Memos.MAX_SUBJECT} characters.")))
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
    "metadata".takeIf { before.metadata != after.metadata },
)
