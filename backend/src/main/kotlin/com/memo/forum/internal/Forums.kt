package com.memo.forum.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.Clearances
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.id.IdGenerator
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.forum.ForumChanged
import com.memo.forum.ForumRole
import com.memo.forum.ForumStatus
import com.memo.org.OrgDirectory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

/** A new committee copied from another: its own key, code and names; the members in office on [startsOn] if asked. */
data class CloneInput(
    val key: String,
    val code: String,
    val name: String,
    val shortName: String,
    val withMembers: Boolean = false,
    val startsOn: LocalDate? = null,
)

/** What an administrator sends to create or edit a forum. */
data class ForumInput(
    val key: String,
    val code: String,
    val name: String,
    val shortName: String,
    val typeId: UUID,
    val parentId: UUID? = null,
    val classificationId: UUID? = null,
)

/**
 * Forums (ADR-0056, design E01 §1, §3): created, edited, dissolved and cloned by holders of `forums.manage`, never
 * deleted; every change audited with before and after. Inside a tenant transaction.
 */
@Service
class Forums(
    private val store: ForumStore,
    private val memberStore: ForumMemberStore,
    private val ids: IdGenerator,
    private val types: ForumTypeStore,
    private val versions: ForumTypeVersionStore,
    private val access: ForumAccess,
    private val clearances: Clearances,
    private val members: ForumMembers,
    private val org: OrgDirectory,
    private val audit: AuditLog,
    private val events: ApplicationEventPublisher,
) {
    /** The forums the actor may see. */
    fun visible(): List<ForumRow> = store.all().filter { access.visible(it) }

    fun get(id: UUID): ForumRow = access.requireVisible(id)

    fun create(input: ForumInput): ForumRow {
        access.requireManage()
        val id = ids.next()
        requireNoProblems(problems(input, id, existing = null))
        val forum =
            ForumRow(
                id = id,
                key = input.key.trim(),
                code = input.code.trim(),
                name = input.name.trim(),
                shortName = input.shortName.trim(),
                typeId = input.typeId,
                parentId = input.parentId,
                classificationId = input.classificationId,
                status = ForumStatus.ACTIVE,
                dissolvedOn = null,
                dissolvedReason = null,
                createdBy = actorOrNull(),
                createdAt = store.now().toInstant(),
                version = 0,
            )
        store.insert(forum)
        audit.record("forum.created", OBJECT_TYPE, id.toString(), after = audited(forum))
        changed(id)
        return checkNotNull(store.find(id))
    }

    /** Name, short name, code, parent and classification; the key and type never change. */
    fun update(
        id: UUID,
        version: Long,
        input: ForumInput,
    ): ForumRow {
        access.requireManage()
        val before = access.requireVisible(id)
        refuseIf(input.key.trim() != before.key, "KEY_FIXED") { "A committee's workflow key never changes." }
        refuseIf(input.typeId != before.typeId, "TYPE_FIXED") { "A committee's type never changes; clone it into a new type." }
        refuseIf(before.codeLockedAt != null && input.code.trim() != before.code, "CODE_LOCKED") {
            "The code is in the numbers of this committee's meetings and no longer changes."
        }
        requireNoProblems(problems(input, id, existing = before))
        val after =
            before.copy(
                code = input.code.trim(),
                name = input.name.trim(),
                shortName = input.shortName.trim(),
                parentId = input.parentId,
                classificationId = input.classificationId,
            )
        if (!store.update(after, version)) throw ConcurrentModificationException("forum $id", version)
        audit.record("forum.updated", OBJECT_TYPE, id.toString(), before = audited(before), after = audited(after))
        changed(id)
        return checkNotNull(store.find(id))
    }

    fun dissolve(
        id: UUID,
        version: Long,
        on: LocalDate?,
        reason: String,
    ): ForumRow {
        access.requireManage()
        val before = access.requireVisible(id)
        refuseIf(before.status == ForumStatus.DISSOLVED, "FORUM_DISSOLVED") { "This committee is already dissolved." }
        val text = reason.trim()
        val tooLong = text.isEmpty() || text.length > MemberRules.MAX_TEXT
        requireNoProblems(listOfNotNull(FieldProblem("reason", "LENGTH", "1 to ${MemberRules.MAX_TEXT} characters.").takeIf { tooLong }))
        val after = before.copy(status = ForumStatus.DISSOLVED, dissolvedOn = on ?: org.today(), dissolvedReason = text)
        if (!store.update(after, version)) throw ConcurrentModificationException("forum $id", version)
        audit.record("forum.dissolved", OBJECT_TYPE, id.toString(), before = audited(before), after = audited(after))
        changed(id)
        return checkNotNull(store.find(id))
    }

    /** A new forum with [source]'s type, parent and classification and, if asked, its members in office on [startsOn]. */
    fun clone(
        sourceId: UUID,
        input: CloneInput,
    ): ForumRow {
        access.requireManage()
        val source = access.requireVisible(sourceId)
        val copy = ForumInput(input.key, input.code, input.name, input.shortName, source.typeId, source.parentId, source.classificationId)
        val created = create(copy)
        val from = input.startsOn ?: org.today()
        val copied =
            if (input.withMembers) {
                memberStore
                    .members(source.id)
                    .filter { it.inOffice(from) }
                    .onEach { members.copyInto(created, it, from) }
                    .size
            } else {
                0
            }
        audit.record("forum.cloned", OBJECT_TYPE, created.id.toString(), after = mapOf("from" to source.id.toString(), "members" to copied))
        return created
    }

    /**
     * Locks the committee's code at its first numbered meeting (design E03 §2): from then on the code is part of meeting
     * and resolution numbers and never changes. Called by the meeting module in the numbering transaction; idempotent.
     */
    fun lockCode(id: UUID): ForumRow {
        val forum = store.find(id) ?: throw ForumNotFoundException()
        if (store.lockCode(id)) {
            audit.record("forum.code_locked", OBJECT_TYPE, id.toString(), after = mapOf("code" to forum.code))
            changed(id)
        }
        return checkNotNull(store.find(id))
    }

    private fun problems(
        input: ForumInput,
        id: UUID,
        existing: ForumRow?,
    ): List<FieldProblem> {
        val key = input.key.trim()
        val code = input.code.trim()
        val name = input.name.trim()
        val shortName = input.shortName.trim()
        val type = types.find(input.typeId)
        val published = type?.let { versions.published(it.id) }
        val level = input.classificationId
        return listOfNotNull(
            FieldProblem("key", "FORMAT", "A lower-case letter, then lower-case letters, digits or hyphens.")
                .takeUnless { KEY.matches(key) },
            FieldProblem("key", "KEY_TAKEN", "Another committee has this key.").takeIf { existing == null && store.keyTaken(key) },
            FieldProblem("code", "FORMAT", "1 to 10 capital letters, digits or hyphens.").takeUnless { ForumTypeChecks.CODE.matches(code) },
            FieldProblem("code", "CODE_TAKEN", "Another committee has this code.").takeIf { store.codeTaken(code, existing?.id) },
            FieldProblem("name", "LENGTH", "1 to $MAX_NAME characters.").takeIf { name.isEmpty() || name.length > MAX_NAME },
            FieldProblem("shortName", "LENGTH", "1 to $MAX_SHORT_NAME characters.").takeIf {
                shortName.isEmpty() || shortName.length > MAX_SHORT_NAME
            },
            FieldProblem("typeId", "unknown", "No such committee type.").takeIf { type == null },
            FieldProblem("typeId", "TYPE_INACTIVE", "The type is switched off or has no published version.").takeIf {
                existing == null && type != null && (!type.active || published == null)
            },
            FieldProblem("classificationId", "unknown", "No such classification level.").takeIf {
                level != null && clearances.level(level) == null
            },
            parentProblem(input.parentId, id),
        )
    }

    /** The parent must exist and must not be the forum or one of its descendants (walking up at most 10 levels). */
    private fun parentProblem(
        parentId: UUID?,
        id: UUID,
    ): FieldProblem? {
        var current = parentId
        var problem: FieldProblem? = null
        var depth = 0
        while (current != null && problem == null) {
            val parent = store.find(current)
            problem =
                when {
                    current == id -> FieldProblem("parentId", "PARENT_CYCLE", "A committee cannot report to itself or to one below it.")
                    parent == null -> FieldProblem("parentId", "unknown", "No such committee.")
                    ++depth > MAX_DEPTH -> FieldProblem("parentId", "PARENT_CYCLE", "At most $MAX_DEPTH levels.")
                    else -> null
                }
            current = parent?.parentId
        }
        return problem
    }

    private fun changed(id: UUID) = events.publishEvent(ForumChanged(store.tenant(), id))

    companion object {
        const val OBJECT_TYPE = "forum"
        const val MAX_DEPTH = 10
        const val MAX_NAME = 200
        const val MAX_SHORT_NAME = 40
        val KEY = Regex("^[a-z][a-z0-9-]{0,99}$")

        fun audited(forum: ForumRow): Map<String, Any?> =
            mapOf(
                "key" to forum.key,
                "code" to forum.code,
                "name" to forum.name,
                "shortName" to forum.shortName,
                "typeId" to forum.typeId.toString(),
                "parentId" to forum.parentId?.toString(),
                "classificationId" to forum.classificationId?.toString(),
                "status" to forum.status.name,
                "dissolvedOn" to forum.dissolvedOn?.toString(),
                "dissolvedReason" to forum.dissolvedReason,
            )

        /** The roles whose holders are told of membership changes. */
        val TOLD = setOf(ForumRole.CHAIR, ForumRole.SECRETARY)
    }
}
