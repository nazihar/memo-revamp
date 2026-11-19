package com.memo.meeting

import com.memo.meeting.internal.DeclarationStore
import com.memo.meeting.internal.ItemStore
import com.memo.meeting.internal.RecusalStore
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** What the declarant intends (design E04 §1.1): leave the room for the item (the default), or ask the chair. */
enum class DeclarationIntent { LEAVE, ASK_CHAIR }

/** Where a declaration came from (design E04 §1.1). REGISTER is reserved for a standing register (Phase 4 Q6). */
enum class DeclarationSource { SELF, ON_BEHALF, MEMO, EARLIER_MEETING, REGISTER }

/**
 * How a declaration or a ruling reached the app: IN_APP by the person themselves, SYSTEM when carried over (T3), and for
 * one recorded by someone else how it was given.
 */
enum class DeclarationHow {
    IN_APP,
    SYSTEM,
    AT_MEETING,
    IN_PERSON,
    EMAIL,
    PHONE,
    ;

    /** Whether a secretary may say it was given this way when recording it for someone. */
    val recorded: Boolean get() = this != IN_APP && this != SYSTEM
}

/** Where a declaration stands (design E04 §1.2). RECUSED and AWAITING_RULING are effective: the person is recused. */
enum class DeclarationState {
    RECUSED,
    AWAITING_RULING,
    TAKING_PART,
    WITHDRAWN,
    ;

    /** Recused for the rule (ADR-0062): a pending ruling withholds the papers too (fail-closed). */
    val effective: Boolean get() = this == RECUSED || this == AWAITING_RULING
}

/** The chair's ruling: the declarant must leave the room for the item, or may take part. */
enum class RulingOutcome { LEAVE, TAKE_PART }

/** The chair's latest ruling on a declaration, as other modules read it. */
data class DeclarationRuling(
    val outcome: RulingOutcome,
    /** The chair or vice-chair who ruled. */
    val by: UUID?,
    /** The secretary who recorded it on the chair's behalf; null when the chair ruled in the app. */
    val onBehalfBy: UUID?,
    val how: DeclarationHow,
    val note: String?,
    val at: Instant,
)

/**
 * A declaration of interest as other modules read it (E06's minutes, design E04 §2). The [nature] is business content in
 * the declarant's words: the caller decides who may read it (the declarant and the officers, ADR-0062).
 */
data class DeclarationRef(
    val id: UUID,
    val meetingId: UUID,
    val itemId: UUID,
    val userId: UUID,
    val nature: String,
    val intent: DeclarationIntent,
    val state: DeclarationState,
    val source: DeclarationSource,
    val how: DeclarationHow,
    val declaredAt: Instant,
    /** Made while the meeting was in progress (or recorded afterwards as made in the room). */
    val atMeeting: Boolean,
    val ruling: DeclarationRuling?,
)

/**
 * Who is recused from what (ADR-0062, design E04 §1.6, §2), for E05's quorum, E06's decisions and minutes and E07's
 * actions: a person is recused from an item when they hold an effective declaration (RECUSED or AWAITING_RULING) on it or
 * on its parent. Read now, not at publication. Inside a tenant transaction; no access decision is made here. Changes are
 * told by [DeclarationChanged].
 */
@Component
class Recusals(
    private val store: DeclarationStore,
    private val recusals: RecusalStore,
    private val items: ItemStore,
) {
    /** Whether [userId] is recused from [itemId] (an item of the agenda, `agenda_item.id`). */
    fun isRecused(
        userId: UUID,
        itemId: UUID,
    ): Boolean = userId in recusedFrom(itemId)

    /** The people recused from [itemId]: effective declarations on it or on its parent. */
    fun recusedFrom(itemId: UUID): Set<UUID> = recusals.recusedOn(listOfNotNull(itemId, items.find(itemId)?.parentId))

    /**
     * The people recused from each item of [meetingId] (the draft's items, a sub-item inheriting its parent's, and any item
     * a declaration is on), for the items with at least one.
     */
    fun ofMeeting(meetingId: UUID): Map<UUID, Set<UUID>> {
        val declared =
            recusals
                .effective(meetingId)
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.toSet() }
        if (declared.isEmpty()) return emptyMap()
        val parents = items.live(meetingId).associate { it.id to it.parentId }
        return (parents.keys + declared.keys)
            .associateWith { item -> declared[item].orEmpty() + parents[item]?.let { declared[it] }.orEmpty() }
            .filterValues { it.isNotEmpty() }
    }

    /** Every declaration of [meetingId], withdrawn ones included, the oldest first (E06's minutes). */
    fun declarationsFor(meetingId: UUID): List<DeclarationRef> =
        store.ofMeeting(meetingId).map { row ->
            DeclarationRef(
                id = row.id,
                meetingId = row.meetingId,
                itemId = row.itemId,
                userId = row.userId,
                nature = row.nature,
                intent = row.intent,
                state = row.state,
                source = row.source,
                how = row.how,
                declaredAt = row.declaredAt,
                atMeeting = row.atMeeting,
                ruling = row.ruling?.let { DeclarationRuling(it.outcome, it.by, it.onBehalfBy, it.how, it.note, it.at) },
            )
        }
}
