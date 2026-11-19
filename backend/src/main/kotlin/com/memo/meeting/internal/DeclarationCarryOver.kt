package com.memo.meeting.internal

import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.MEETING_DECLARATION
import com.memo.jooq.tables.references.MEETING_ITEM
import com.memo.meeting.DeclarationHow
import com.memo.meeting.DeclarationIntent
import com.memo.meeting.DeclarationSource
import com.memo.meeting.DeclarationState
import com.memo.meeting.MeetingStatus
import com.memo.meeting.Recusals
import com.memo.memo.MemoInterestLinks
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import java.time.Clock
import java.util.UUID

/**
 * Declarations carried over (ADR-0062, design E04 §1.4), so a person never declares the same matter twice:
 *  - from an earlier placement of the same submission (deferred, removed, or its meeting cancelled), when it is placed
 *    again: RECUSED and AWAITING_RULING arrive RECUSED; TAKING_PART arrives AWAITING_RULING, as a ruling belongs to its
 *    meeting's chair; withdrawn ones do not carry. The declarant's words come with it (`source` EARLIER_MEETING);
 *  - from the memo's interests (SOD-4), at placement and again at each publication: RECUSED, with the fixed nature
 *    "Interest declared on memo {number}", never the memo's reason (`source` MEMO).
 *
 * Only people in the meeting's audience or holding an officer's seat receive one, and only on an item on which they have
 * never held a declaration: one withdrawn with a reason is not carried again. Each is recorded by the actor of the
 * placement or publication (`how` SYSTEM), audited as `meeting.declaration.made` and told to the declarant. In the
 * placing or publishing transaction.
 */
@Component
class DeclarationCarryOver(
    private val store: DeclarationStore,
    private val carried: CarryOverStore,
    private val access: DeclarationAccess,
    private val effects: DeclarationEffects,
    private val memoInterests: MemoInterestLinks,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    /** The submission item [item] was placed on [meeting]: carries from its earlier placement, then from its memo. */
    fun placed(
        meeting: MeetingRow,
        item: ItemRow,
    ) {
        val earlier =
            item.submissionId
                ?.let { carried.onEarlierPlacement(it, item.id) }
                .orEmpty()
                .filter { it.state != DeclarationState.WITHDRAWN }
        val interests = item.memoId?.let { memoInterests.ofMemos(listOf(it))[it] }
        if (earlier.isEmpty() && interests == null) return
        val scene = Scene(meeting, access.agenda(meeting), access.audienceAndOfficers(meeting), actorOrNull())
        val target = checkNotNull(scene.agenda.item(item.id, officer = true)) { "item ${item.id} is on the draft" }
        earlier.filter { it.userId in scene.people }.forEach { carry(scene, target, fromEarlier(scene, target, it)) }
        interests?.users?.filter { it in scene.people }?.sorted()?.forEach { user ->
            carry(scene, target, fromMemo(scene, target, user, interests.memoId, interests.reference))
        }
    }

    /** Revision of [meeting] was published from the draft's [items]: the memo interests of its submission items carry. */
    fun published(
        meeting: MeetingRow,
        items: List<ItemRow>,
    ) {
        val submissions = items.filter { it.kind == ItemKind.SUBMISSION && it.memoId != null }
        if (submissions.isEmpty()) return
        val interests = memoInterests.ofMemos(submissions.mapNotNull { it.memoId }.toSet())
        val concerned = submissions.filter { it.memoId in interests }
        if (concerned.isEmpty()) return
        val held = carried.declarants(concerned.map { it.id })
        val scene = Scene(meeting, access.agenda(meeting), access.audienceAndOfficers(meeting), actorOrNull())
        for (item in concerned) {
            val memo = interests.getValue(checkNotNull(item.memoId))
            val target = scene.agenda.item(item.id, officer = true) ?: continue
            val users = memo.users.filter { it in scene.people } - held[item.id].orEmpty()
            users.sorted().forEach { carry(scene, target, fromMemo(scene, target, it, memo.memoId, memo.reference)) }
        }
    }

    /** Writes [row] with its first event and its effects; nothing when the person already holds an open one (`ONE_OPEN`). */
    private fun carry(
        scene: Scene,
        item: DeclarableItem,
        row: DeclarationRow,
    ) {
        val event =
            DeclarationEvent(
                ids.next(),
                row.id,
                DeclarationEventKind.DECLARED,
                row.state,
                scene.actor,
                null,
                DeclarationHow.SYSTEM,
                null,
                false,
                row.declaredAt,
            )
        if (store.insert(row, event)) effects.made(scene.meeting, scene.actor, item, row, null)
    }

    private fun fromEarlier(
        scene: Scene,
        item: DeclarableItem,
        earlier: DeclarationRow,
    ): DeclarationRow {
        val awaiting = earlier.state == DeclarationState.TAKING_PART
        return row(
            scene,
            item,
            earlier.userId,
            earlier.nature,
            DeclarationSource.EARLIER_MEETING,
            earlier.id,
            if (awaiting) DeclarationIntent.ASK_CHAIR else DeclarationIntent.LEAVE,
            if (awaiting) DeclarationState.AWAITING_RULING else DeclarationState.RECUSED,
        )
    }

    private fun fromMemo(
        scene: Scene,
        item: DeclarableItem,
        user: UUID,
        memoId: UUID,
        reference: String?,
    ): DeclarationRow =
        row(
            scene,
            item,
            user,
            memoNature(reference),
            DeclarationSource.MEMO,
            memoId,
            DeclarationIntent.LEAVE,
            DeclarationState.RECUSED,
        )

    @Suppress("LongParameterList")
    private fun row(
        scene: Scene,
        item: DeclarableItem,
        user: UUID,
        nature: String,
        source: DeclarationSource,
        sourceRef: UUID,
        intent: DeclarationIntent,
        state: DeclarationState,
    ) = DeclarationRow(
        id = ids.next(),
        meetingId = scene.meeting.id,
        itemId = item.id,
        forumId = scene.meeting.forumId,
        userId = user,
        nature = nature,
        intent = intent,
        source = source,
        sourceRef = sourceRef,
        recordedBy = scene.actor,
        how = DeclarationHow.SYSTEM,
        howNote = null,
        declaredAt = clock.instant(),
        atMeeting = scene.meeting.status == MeetingStatus.IN_PROGRESS,
        correction = false,
        revision = scene.agenda.latest.takeIf { it > 0 },
        state = state,
        ruling = null,
        version = 0,
    )

    /** The meeting being placed on or published, its items, who may receive a declaration, and who acts. */
    private data class Scene(
        val meeting: MeetingRow,
        val agenda: DeclarationAgenda,
        val people: Set<UUID>,
        val actor: UUID?,
    )

    companion object {
        /** The fixed nature of a declaration carried from the memo: its number, never the reason given on the memo. */
        fun memoNature(reference: String?): String =
            if (reference == null) "Interest declared on the memo of this item" else "Interest declared on memo $reference"
    }
}

/**
 * "Also reads the memo" (design E04 §1.4, ADR-0062): recusal does not take away the access someone has to a memo by the
 * memo's own rule (an approver, a copy-to). For each live submission item of a meeting, the people recused from it who
 * read its memo now, so the meeting register (T4) can flag them to the secretary, who acts through the memo's access.
 * Inside a tenant transaction; no access decision is made here.
 */
@Component
class RecusedMemoReaders(
    private val recusals: Recusals,
    private val items: ItemStore,
    private val memoInterests: MemoInterestLinks,
) {
    /** By item of [meetingId], the people recused from it who also read its memo; items with none are left out. */
    fun of(meetingId: UUID): Map<UUID, Set<UUID>> {
        val recused = recusals.ofMeeting(meetingId)
        if (recused.isEmpty()) return emptyMap()
        return items
            .live(meetingId)
            .mapNotNull { item ->
                val memoId = item.memoId ?: return@mapNotNull null
                val people = recused[item.id] ?: return@mapNotNull null
                item.id to memoInterests.readersAmong(memoId, people)
            }.toMap()
            .filterValues { it.isNotEmpty() }
    }
}

/** What carrying declarations over reads (design E04 §1.4), inside a tenant transaction. */
@Repository
class CarryOverStore(
    private val tenantDsl: TenantDsl,
    private val declarations: DeclarationStore,
) {
    /** The people who hold or held a declaration (withdrawn ones included) on each of [itemIds] that has any. */
    fun declarants(itemIds: Collection<UUID>): Map<UUID, Set<UUID>> =
        tenantDsl
            .current()
            .select(MEETING_DECLARATION.ITEM_ID, MEETING_DECLARATION.USER_ID)
            .from(MEETING_DECLARATION)
            .where(MEETING_DECLARATION.ITEM_ID.`in`(itemIds.toSet()))
            .fetch()
            .groupBy({ it.value1()!! }, { it.value2()!! })
            .mapValues { it.value.toSet() }

    /**
     * The declarations on the last earlier placement of [submissionId] before the item [itemId]: of the items the
     * submission was placed on before (removed or not, in any meeting) that carry declarations, the latest one's, the
     * oldest declaration first. Item ids are time-ordered (UUIDv7). Read from the declarations, a small table, joined to
     * their items by key, so no index on the items' submission is needed for removed items.
     */
    fun onEarlierPlacement(
        submissionId: UUID,
        itemId: UUID,
    ): List<DeclarationRow> {
        val found =
            tenantDsl
                .current()
                .select(MEETING_DECLARATION.ID, MEETING_DECLARATION.ITEM_ID)
                .from(MEETING_DECLARATION)
                .join(MEETING_ITEM)
                .on(MEETING_ITEM.TENANT_ID.eq(MEETING_DECLARATION.TENANT_ID), MEETING_ITEM.ID.eq(MEETING_DECLARATION.ITEM_ID))
                .where(MEETING_ITEM.SUBMISSION_ID.eq(submissionId), MEETING_ITEM.ID.lt(itemId))
                .orderBy(MEETING_DECLARATION.DECLARED_AT, MEETING_DECLARATION.ID)
                .fetch()
        val last = found.maxOfOrNull { it.value2()!! }
        return found.filter { it.value2() == last }.mapNotNull { declarations.find(it.value1()!!) }
    }
}
