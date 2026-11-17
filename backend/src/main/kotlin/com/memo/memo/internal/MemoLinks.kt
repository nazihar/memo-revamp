package com.memo.memo.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Policy
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.jooq.tables.references.MEMO_LINK
import com.memo.jooq.tables.references.MEMO_PARTICIPANT
import io.swagger.v3.oas.annotations.media.Schema
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import com.memo.jooq.tables.references.MEMO_ as MEMO

/** How one memo relates to another (design E01 §1). */
enum class LinkKind { REFERS_TO, SUPERSEDES, FOLLOWS_UP }

/** A standing link as a reader sees it: the other memo, shown only because they may read it. */
data class MemoLinkView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: LinkKind,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val memoId: UUID,
    val reference: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val subject: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val status: MemoStatus,
    /** Whether the reader may remove it (they made it, and the memo is still being drafted). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val removable: Boolean = false,
)

private data class LinkRow(
    val id: UUID,
    val memoId: UUID,
    val createdBy: UUID,
)

/** Links between memos (design E01 §1): standing ones, both ways, and only to memos the reader may read. */
@Repository
class MemoLinkStore(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
) {
    fun insert(
        memoId: UUID,
        target: UUID,
        kind: LinkKind,
        by: UUID,
        now: OffsetDateTime,
    ): UUID {
        val id = ids.next()
        tenantDsl
            .current()
            .insertInto(MEMO_LINK)
            .set(MEMO_LINK.ID, id)
            .set(MEMO_LINK.TENANT_ID, tenantDsl.tenant().value)
            .set(MEMO_LINK.MEMO_ID, memoId)
            .set(MEMO_LINK.TARGET_MEMO_ID, target)
            .set(MEMO_LINK.KIND, kind.name)
            .set(MEMO_LINK.CREATED_BY, by)
            .set(MEMO_LINK.CREATED_AT, now)
            .execute()
        return id
    }

    /** Records the removal of a standing link; false when it was already removed. */
    fun remove(
        id: UUID,
        by: UUID,
        now: OffsetDateTime,
    ): Boolean =
        tenantDsl
            .current()
            .update(MEMO_LINK)
            .set(MEMO_LINK.REMOVED_BY, by)
            .set(MEMO_LINK.REMOVED_AT, now)
            .where(MEMO_LINK.ID.eq(id), MEMO_LINK.REMOVED_AT.isNull)
            .execute() == 1

    fun standing(id: UUID): Triple<UUID, UUID, UUID>? =
        tenantDsl
            .current()
            .select(MEMO_LINK.ID, MEMO_LINK.MEMO_ID, MEMO_LINK.CREATED_BY)
            .from(MEMO_LINK)
            .where(MEMO_LINK.ID.eq(id), MEMO_LINK.REMOVED_AT.isNull)
            .fetchOne { LinkRow(it.value1()!!, it.value2()!!, it.value3()!!) }
            ?.let { Triple(it.id, it.memoId, it.createdBy) }

    fun count(memoId: UUID): Int = tenantDsl.current().fetchCount(MEMO_LINK, MEMO_LINK.MEMO_ID.eq(memoId), MEMO_LINK.REMOVED_AT.isNull)

    fun exists(
        memoId: UUID,
        target: UUID,
    ): Boolean =
        tenantDsl
            .current()
            .fetchExists(MEMO_LINK, MEMO_LINK.MEMO_ID.eq(memoId), MEMO_LINK.TARGET_MEMO_ID.eq(target), MEMO_LINK.REMOVED_AT.isNull)

    /**
     * The standing links of [memoId] in one direction ([outgoing]: from it; else to it), to memos [reader] takes part in
     * and is cleared for, with who made each.
     */
    fun links(
        memoId: UUID,
        outgoing: Boolean,
        reader: RegisterReader,
    ): List<Pair<MemoLinkView, UUID>> {
        val here: Field<UUID?> = if (outgoing) MEMO_LINK.MEMO_ID else MEMO_LINK.TARGET_MEMO_ID
        val other: Field<UUID?> = if (outgoing) MEMO_LINK.TARGET_MEMO_ID else MEMO_LINK.MEMO_ID
        return tenantDsl
            .current()
            .select(MEMO_LINK.ID, MEMO_LINK.KIND, MEMO.ID, MEMO.REFERENCE, MEMO.SUBJECT, MEMO.STATUS, MEMO_LINK.CREATED_BY)
            .from(MEMO_LINK)
            .join(MEMO)
            .on(MEMO.TENANT_ID.eq(MEMO_LINK.TENANT_ID), MEMO.ID.eq(other))
            .where(here.eq(memoId), MEMO_LINK.REMOVED_AT.isNull, MEMO.CLASSIFICATION_RANK.le(reader.clearanceRank))
            .andExists(
                DSL
                    .selectOne()
                    .from(MEMO_PARTICIPANT)
                    .where(MEMO_PARTICIPANT.MEMO_ID.eq(MEMO.ID), MEMO_PARTICIPANT.USER_ID.eq(reader.user)),
            ).orderBy(MEMO_LINK.CREATED_AT)
            .fetch { r ->
                MemoLinkView(
                    r.value1()!!,
                    LinkKind.valueOf(r.value2()!!),
                    r.value3()!!,
                    r.value4(),
                    r.value5()!!,
                    MemoStatus.valueOf(r.value6()!!),
                ) to r.value7()!!
            }
    }

    /** The memo numbered [reference], without any visibility check (the caller decides). */
    fun byReference(reference: String): UUID? =
        tenantDsl
            .current()
            .select(MEMO.ID)
            .from(MEMO)
            .where(MEMO.REFERENCE.eq(reference))
            .fetchOne(MEMO.ID)
}

/**
 * Links between memos (design E01 §1, §3): a participant links a memo they may read to another memo they may read, by its
 * number, while the memo is not closed or discarded (at most [MAX_LINKS] standing links); the person who made a link may
 * remove it while the memo is a draft or returned. Removal is recorded, never deleted. Inside a tenant transaction.
 */
@Component
class MemoLinks(
    private val memos: Memos,
    private val store: MemoLinkStore,
    private val memoStore: MemoStore,
    private val policy: Policy,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    fun add(
        memoId: UUID,
        targetReference: String,
        kind: LinkKind,
    ): UUID {
        val memo = memos.get(memoId)
        val user = actor()
        refuseIf(memo.status in MemoViewsRules.UNLINKABLE, "WRONG_STATE") { "A closed or discarded memo takes no new links." }
        // A number the reader cannot open answers like one that does not exist.
        val target =
            store.byReference(targetReference.trim())?.takeIf { memoStore.visible(it, user, policy.clearanceRank()) != null }
                ?: throw MemoRejectedException(
                    listOf(FieldProblem("targetReference", "LINK_TARGET_UNREADABLE", "No memo you can open has this number.")),
                )
        val problems =
            listOfNotNull(
                FieldProblem("targetReference", "SELF", "A memo cannot link to itself.").takeIf { target == memoId },
                FieldProblem("targetReference", "LINK_EXISTS", "These memos are linked already.").takeIf { store.exists(memoId, target) },
                FieldProblem("targetReference", "LINK_LIMIT", "At most $MAX_LINKS links.").takeIf { store.count(memoId) >= MAX_LINKS },
            )
        if (problems.isNotEmpty()) throw MemoRejectedException(problems)
        val id = store.insert(memoId, target, kind, user, now())
        audit.record(
            "memo.link.added",
            Memos.OBJECT_TYPE,
            memoId.toString(),
            after =
                mapOf(
                    "target" to target.toString(),
                    "kind" to kind.name,
                ),
        )
        return id
    }

    fun remove(
        memoId: UUID,
        linkId: UUID,
    ) {
        val memo = memos.get(memoId)
        val (_, from, createdBy) = store.standing(linkId)?.takeIf { it.second == memoId } ?: throw MemoNotFoundException("link")
        if (createdBy != actor()) throw ForbiddenException(null)
        refuseIf(!memo.status.editable, "WRONG_STATE") { "Links can be removed only while the memo is a draft or returned to you." }
        if (store.remove(linkId, actor(), now())) {
            audit.record("memo.link.removed", Memos.OBJECT_TYPE, from.toString(), after = mapOf("link" to linkId.toString()))
        }
    }

    /** The memo's links to and from memos the reader may read. */
    fun of(memo: Memo): Pair<List<MemoLinkView>, List<MemoLinkView>> {
        val reader = RegisterReader(actor(), policy.clearanceRank())
        val outgoing =
            store.links(memo.id, outgoing = true, reader).map { (link, by) ->
                link.copy(removable = by == reader.user && memo.status.editable)
            }
        return outgoing to store.links(memo.id, outgoing = false, reader).map { it.first }
    }

    private fun actor(): UUID = checkNotNull(actorOrNull()) { "links are made by signed-in users" }

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    companion object {
        const val MAX_LINKS = 50
    }
}

/** Shared view rules of memos (design E01 §1). */
object MemoViewsRules {
    /** Memos nothing more is added to: no new links. */
    val UNLINKABLE = setOf(MemoStatus.CLOSED, MemoStatus.DISCARDED)
}
