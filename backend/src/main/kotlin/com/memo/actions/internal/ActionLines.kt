package com.memo.actions.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.ACTIONS_CONTRIBUTOR
import com.memo.jooq.tables.references.ACTIONS_ENTRY
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * An action's contributors and its timeline (design E07 §1.3, §1.6), inside a tenant transaction and under the action's
 * lock: a contributor's row is only ever ended, once (a person added again is a new row); entries are append-only.
 */
@Repository
class ActionLines(
    private val tenantDsl: TenantDsl,
    private val json: JsonMapper,
) {
    /** The current contributors of each of [actionIds], in the order they were added. */
    fun contributors(actionIds: Collection<UUID>): Map<UUID, List<ContributorRow>> {
        if (actionIds.isEmpty()) return emptyMap()
        val c = ACTIONS_CONTRIBUTOR
        return tenantDsl
            .current()
            .selectFrom(c)
            .where(c.ACTION_ID.`in`(actionIds), c.REMOVED_AT.isNull)
            .orderBy(c.ADDED_AT, c.USER_ID)
            .fetch { ContributorRow(it.actionId, it.userId, it.addedBy, it.addedAt.toInstant()) }
            .groupBy { it.actionId }
    }

    /** The current contributors of [actionId]. */
    fun contributorsOf(actionId: UUID): List<UUID> = contributors(listOf(actionId))[actionId].orEmpty().map { it.userId }

    /** Sets the current contributors of [actionId] to [users]: the others' rows are ended, the new ones added. */
    fun setContributors(
        actionId: UUID,
        users: List<UUID>,
        by: UUID,
        at: Instant,
    ) {
        val current = contributorsOf(actionId)
        val removed = current - users.toSet()
        val c = ACTIONS_CONTRIBUTOR
        val dsl = tenantDsl.current()
        if (removed.isNotEmpty()) {
            dsl
                .update(c)
                .set(c.REMOVED_BY, by)
                .set(c.REMOVED_AT, at.atOffset(ZoneOffset.UTC))
                .where(c.ACTION_ID.eq(actionId), c.USER_ID.`in`(removed), c.REMOVED_AT.isNull)
                .execute()
        }
        (users - current.toSet()).forEach { user ->
            dsl
                .insertInto(c)
                .set(c.ID, UUID.randomUUID())
                .set(c.TENANT_ID, tenantDsl.tenant().value)
                .set(c.ACTION_ID, actionId)
                .set(c.USER_ID, user)
                .set(c.ADDED_BY, by)
                .set(c.ADDED_AT, at.atOffset(ZoneOffset.UTC))
                .execute()
        }
    }

    /**
     * Appends an entry of [kind] to [actionId]'s timeline; returns its number. [more] carries what people wrote and how
     * (an update's text, confidence and files, on whose behalf, the entry it corrects; design E07 §1.6).
     */
    fun entry(
        actionId: UUID,
        kind: String,
        actorId: UUID?,
        details: Map<String, Any?>,
        at: Instant,
        more: EntryMore = EntryMore(),
    ): Int {
        val e = ACTIONS_ENTRY
        val dsl = tenantDsl.current()
        val seq =
            (
                dsl
                    .select(DSL.max(e.SEQ))
                    .from(e)
                    .where(e.ACTION_ID.eq(actionId))
                    .fetchOne()
                    ?.value1() ?: 0
            ) + 1
        dsl
            .insertInto(e)
            .set(e.TENANT_ID, tenantDsl.tenant().value)
            .set(e.ACTION_ID, actionId)
            .set(e.SEQ, seq)
            .set(e.KIND, kind)
            .set(e.ACTOR_ID, actorId)
            .set(e.DETAILS, JSONB.jsonb(json.writeValueAsString(details)))
            .set(e.ON_BEHALF_OF, more.onBehalfOf)
            .set(e.HOW, more.how?.name)
            .set(e.TEXT, more.text)
            .set(e.CONFIDENCE, more.confidence?.name)
            .set(e.FILE_IDS, more.fileIds.toTypedArray<UUID?>())
            .set(e.CORRECTS_SEQ, more.corrects)
            .set(e.AT, at.atOffset(ZoneOffset.UTC))
            .execute()
        return seq
    }

    /** Those of [actionIds] with an entry of [kind] whose detail [key] is [value] (a marker, such as an update asked for a meeting). */
    fun marked(
        actionIds: Collection<UUID>,
        kind: String,
        key: String,
        value: String,
    ): Set<UUID> {
        if (actionIds.isEmpty()) return emptySet()
        val e = ACTIONS_ENTRY
        return tenantDsl
            .current()
            .selectDistinct(e.ACTION_ID)
            .from(e)
            .where(e.ACTION_ID.`in`(actionIds), e.KIND.eq(kind), DSL.jsonbGetAttributeAsText(e.DETAILS, key).eq(value))
            .fetchSet { checkNotNull(it.value1()) }
    }

    /** Entry [seq] of [actionId]; null when there is none. */
    fun entryOf(
        actionId: UUID,
        seq: Int,
    ): EntryRow? = entries(actionId).firstOrNull { it.seq == seq }

    /** [actionId]'s timeline, the newest first. */
    fun entries(actionId: UUID): List<EntryRow> =
        tenantDsl
            .current()
            .selectFrom(ACTIONS_ENTRY)
            .where(ACTIONS_ENTRY.ACTION_ID.eq(actionId))
            .orderBy(ACTIONS_ENTRY.SEQ.desc())
            .fetch {
                EntryRow(
                    it.actionId,
                    it.seq,
                    it.kind,
                    it.actorId,
                    it.details?.let { d -> json.readValue(d.data(), DETAILS) }.orEmpty(),
                    it.at.toInstant(),
                    EntryMore(
                        onBehalfOf = it.onBehalfOf,
                        how = it.how?.let(How::valueOf),
                        text = it.text,
                        confidence = it.confidence?.let(Confidence::valueOf),
                        fileIds = it.fileIds?.filterNotNull().orEmpty(),
                        corrects = it.correctsSeq,
                    ),
                )
            }

    private companion object {
        val DETAILS = object : TypeReference<Map<String, Any?>>() {}
    }
}
