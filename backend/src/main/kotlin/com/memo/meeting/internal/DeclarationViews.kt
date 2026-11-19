package com.memo.meeting.internal

import com.memo.audit.AuditLog
import com.memo.common.web.refuseIf
import com.memo.identity.UserSummaries
import com.memo.meeting.DeclarationHow
import com.memo.meeting.DeclarationIntent
import com.memo.meeting.DeclarationSource
import com.memo.meeting.DeclarationState
import com.memo.meeting.RulingOutcome
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** The chair's latest ruling on a declaration. */
data class RulingResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val outcome: RulingOutcome,
    /** The chair or vice-chair who ruled. */
    val by: PersonResponse?,
    /** The secretary who recorded it on the chair's behalf; null when the chair ruled in the app. */
    val onBehalfBy: PersonResponse?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val how: DeclarationHow,
    val note: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val at: Instant,
)

/** A declaration of interest, as its declarant and the committee's officers see it (design E04 §1.1). */
data class DeclarationResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val itemId: UUID,
    /** "5", "5.1"; null when the item is not numbered (waiting for the chair, or no longer on the agenda). */
    val itemNumber: String?,
    /** Null when the viewer may not see the item's title ("Restricted item"). */
    val itemTitle: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val user: PersonResponse,
    /** What the interest is, in the declarant's words (or "Interest declared on memo …" when carried over). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val nature: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val intent: DeclarationIntent,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val source: DeclarationSource,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val how: DeclarationHow,
    /** For one recorded by someone else: how it was given ("email of 4 Dec"). */
    val howNote: String?,
    val recordedBy: PersonResponse?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val declaredAt: Instant,
    /** Made while the meeting was in progress (or recorded afterwards as made in the room). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val atMeeting: Boolean,
    /** Recorded by a secretary after the meeting was held. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val correction: Boolean,
    /** The published revision the declarant was reading; null before publication. */
    val revision: Int?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: DeclarationState,
    val ruling: RulingResponse?,
    /** Send it as If-Match with a change. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val version: Long,
)

/** Someone else's open declaration on an item: who, and where it stands (never the nature). */
data class DeclarationLineResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val user: PersonResponse,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: DeclarationState,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val intent: DeclarationIntent,
)

/** An item to review for interests: added since the revision the person last confirmed. */
data class ReviewItemResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val itemId: UUID,
    val number: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val title: String,
)

/** The actor's declarations in a meeting and their review (design E04 §1.5, §3). */
data class MyDeclarationsResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingId: UUID,
    /** The latest published revision; 0 before the first. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val latestRevision: Int,
    /** The latest revision the actor confirmed "I have declared my interests" for; null before a first confirmation. */
    val reviewedRevision: Int?,
    /** Every declaration the actor holds in the meeting, withdrawn ones included, the oldest first. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val declarations: List<DeclarationResponse>,
    /** Items of the latest revision added since [reviewedRevision] (all of them before a first confirmation). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val toReview: List<ReviewItemResponse>,
)

/** Declarations as people read them: names resolved, titles only where the viewer sees them. */
@Component
class DeclarationViews(
    private val users: UserSummaries,
    private val access: DeclarationAccess,
) {
    /** [row] as the actor of [scene] sees it. */
    fun response(
        row: DeclarationRow,
        scene: DeclarationScene,
    ): DeclarationResponse {
        val item = scene.agenda.item(row.itemId, officer = true)
        val published = item?.published
        val title =
            when {
                item == null -> null
                scene.officer -> item.title
                published != null && item.id in access.titles(scene.agenda, scene.actor) -> item.title
                else -> null
            }
        return of(row, item?.number, title, names(listOf(row)))
    }

    /** The people a list of declarations names: declarants, recorders and the rulings' people. */
    fun names(rows: Collection<DeclarationRow>): Map<UUID, String> =
        users.names(rows.flatMap { listOfNotNull(it.userId, it.recordedBy, it.ruling?.by, it.ruling?.onBehalfBy) }.toSet())

    companion object {
        fun of(
            row: DeclarationRow,
            number: String?,
            title: String?,
            names: Map<UUID, String>,
        ): DeclarationResponse {
            val person = { id: UUID -> PersonResponse(id, names[id].orEmpty()) }
            return DeclarationResponse(
                id = row.id,
                meetingId = row.meetingId,
                itemId = row.itemId,
                itemNumber = number,
                itemTitle = title,
                user = person(row.userId),
                nature = row.nature,
                intent = row.intent,
                source = row.source,
                how = row.how,
                howNote = row.howNote,
                recordedBy = row.recordedBy?.let(person),
                declaredAt = row.declaredAt,
                atMeeting = row.atMeeting,
                correction = row.correction,
                revision = row.revision,
                state = row.state,
                ruling =
                    row.ruling?.let {
                        RulingResponse(it.outcome, it.by?.let(person), it.onBehalfBy?.let(person), it.how, it.note, it.at)
                    },
                version = row.version,
            )
        }

        /** Someone's open declaration as a line on an item. */
        fun line(
            row: DeclarationRow,
            names: Map<UUID, String>,
        ) = DeclarationLineResponse(row.id, PersonResponse(row.userId, names[row.userId].orEmpty()), row.state, row.intent)
    }
}

/**
 * The review (design E04 §1.5): the actor's declarations in a meeting and the items to review, and their confirmation "I
 * have declared my interests in this agenda" against the latest revision (409 `STALE_REVISION` for another). Anyone in
 * the audience and the officers; `forum.read` holders outside it are 403.
 */
@Service
class DeclarationReviews(
    private val service: Declarations,
    private val access: DeclarationAccess,
    private val store: DeclarationStore,
    private val revisions: RevisionStore,
    private val views: DeclarationViews,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    fun mine(meetingId: UUID): MyDeclarationsResponse {
        val scene = service.scene(meetingId)
        forbidIf(scene.relation == MeetingRelation.READER)
        val rows = store.ofMeeting(scene.meeting.id, scene.actor)
        val reviewed = store.reviewed(scene.meeting.id, scene.actor)
        return MyDeclarationsResponse(
            meetingId = scene.meeting.id,
            latestRevision = scene.agenda.latest,
            reviewedRevision = reviewed,
            declarations = rows.map { views.response(it, scene) },
            toReview = toReview(scene, reviewed),
        )
    }

    fun review(
        meetingId: UUID,
        revision: Int,
    ): MyDeclarationsResponse {
        val scene = service.scene(meetingId)
        forbidIf(scene.relation == MeetingRelation.READER)
        DeclarationRules.window(scene.meeting.status, DeclarationAct.REVIEW, secretary = false)
        refuseIf(scene.agenda.latest == 0 || revision != scene.agenda.latest, "STALE_REVISION") {
            "Review the latest revision of the agenda (revision ${scene.agenda.latest})."
        }
        if (store.review(scene.meeting.id, scene.actor, revision, clock.instant())) {
            audit.record(
                "meeting.declarations.reviewed",
                MeetingLifecycle.OBJECT_TYPE,
                scene.meeting.id.toString(),
                after = mapOf("revision" to revision),
            )
        }
        return mine(meetingId)
    }

    /**
     * The items of the latest revision whose title the actor sees, first published after [reviewed] (all of them before a
     * first confirmation): standing items and breaks are not asked about.
     */
    private fun toReview(
        scene: DeclarationScene,
        reviewed: Int?,
    ): List<ReviewItemResponse> {
        val first = revisions.firstPublished(scene.meeting.id)
        val titles = if (scene.officer) scene.agenda.published.keys else access.titles(scene.agenda, scene.actor)
        return scene.agenda.published.values
            .filter { it.kind == ItemKind.ITEM || it.kind == ItemKind.SUBMISSION }
            .filter { (first[it.itemId] ?: 0) > (reviewed ?: 0) && it.itemId in titles }
            .map { ReviewItemResponse(it.itemId, it.number, it.title) }
    }
}
