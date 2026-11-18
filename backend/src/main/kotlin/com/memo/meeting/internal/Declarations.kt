package com.memo.meeting.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.ForbiddenException
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.id.IdGenerator
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumRole
import com.memo.meeting.MeetingStatus
import com.memo.notification.NotificationKind
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

/** A declaration to make: for oneself, or recorded for [onBehalfOf] by an officer, saying [how] it was given. */
data class DeclareInput(
    val nature: String?,
    val intent: DeclarationIntent = DeclarationIntent.LEAVE,
    val onBehalfOf: UUID? = null,
    val how: DeclarationHow? = null,
    val howNote: String? = null,
    /** Required for a secretary's correction after the meeting is held. */
    val reason: String? = null,
)

/** A ruling: by the chair in the app, or recorded [onBehalf] of the chair by a secretary, saying [how] it was given. */
data class RulingInput(
    val outcome: RulingOutcome,
    val note: String? = null,
    val onBehalf: Boolean = false,
    val how: DeclarationHow? = null,
)

/** A meeting as a declaration change sees it: the actor's relation to it and its items. */
data class DeclarationScene(
    val meeting: MeetingRow,
    val relation: MeetingRelation,
    val agenda: DeclarationAgenda,
    val actor: UUID,
    /** The actor's seat on the committee today. */
    val seat: ForumRole?,
) {
    val officer: Boolean get() = relation == MeetingRelation.OFFICER

    /** The chair or vice-chair, who may be the one to rule (design E04 §1.2). */
    val chairSeat: Boolean get() = seat == ForumRole.CHAIR || seat == ForumRole.VICE_CHAIR

    /** The item a declaration is on, whatever became of it in the draft (for the recusal check of officers). */
    fun itemOf(declaration: DeclarationRow): DeclarableItem =
        agenda.item(declaration.itemId, officer = true)
            ?: DeclarableItem(declaration.itemId, null, ItemKind.ITEM, "", true, null, null)
}

/**
 * Declarations of interest (ADR-0062, design E04 §1.1–§1.3): a person declares on an item they see; an officer records
 * one for someone in the audience; the declarant changes their intent; the declarant or an officer withdraws it with a
 * reason. Every change is a guarded update with an event row, audited with hashes of what people wrote (never the
 * text), and told to the people concerned. An invisible meeting, item or declaration is 404.
 */
@Service
class Declarations(
    private val guard: MeetingGuard,
    private val access: MeetingAccess,
    private val declarations: DeclarationAccess,
    private val store: DeclarationStore,
    private val effects: DeclarationEffects,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    /** Declares on [itemId] for the actor, or records it for `onBehalfOf`; the new declaration. */
    fun declare(
        meetingId: UUID,
        itemId: UUID,
        input: DeclareInput,
    ): DeclarationRow {
        val scene = scene(meetingId)
        val target = input.onBehalfOf ?: scene.actor
        val forSomeone = target != scene.actor
        val item = item(scene, itemId, forSomeone)
        val correction = DeclarationRules.window(scene.meeting.status, act(forSomeone), access.manages(scene.meeting.forumId))
        if (forSomeone) requireRecorder(scene, item, target)
        val (nature, natureProblem) = DeclarationRules.nature(input.nature)
        val (howNote, noteProblem) = DeclarationRules.optional("howNote", input.howNote, DeclarationRules.MAX_HOW_NOTE)
        val (reason, reasonProblem) = DeclarationRules.reason(input.reason)
        requireNoProblems(
            listOfNotNull(
                natureProblem,
                noteProblem,
                if (forSomeone) DeclarationRules.how(input.how) else null,
                reasonProblem.takeIf { correction },
            ),
        )
        val state = DeclarationRules.initial(input.intent)
        val now = clock.instant()
        val row =
            DeclarationRow(
                id = ids.next(),
                meetingId = scene.meeting.id,
                itemId = item.id,
                forumId = scene.meeting.forumId,
                userId = target,
                nature = nature,
                intent = input.intent,
                source = if (forSomeone) DeclarationSource.ON_BEHALF else DeclarationSource.SELF,
                sourceRef = null,
                recordedBy = scene.actor,
                how = if (forSomeone) checkNotNull(input.how) else DeclarationHow.IN_APP,
                howNote = howNote.takeIf { forSomeone },
                declaredAt = now,
                atMeeting = scene.meeting.status == MeetingStatus.IN_PROGRESS || correction,
                correction = correction,
                revision = scene.agenda.latest.takeIf { it > 0 },
                state = state,
                ruling = null,
                version = 0,
            )
        val event =
            DeclarationEvent(ids.next(), row.id, DeclarationEventKind.DECLARED, state, scene.actor, null, row.how, reason, correction, now)
        if (!store.insert(row, event)) throw OneOpenException(store.open(item.id, target)?.id)
        effects.declared(scene, item, row, reason)
        return row
    }

    /** The declarant changes their intent: asking the chair waits for a ruling; choosing to leave is recused. */
    fun changeIntent(
        meetingId: UUID,
        declarationId: UUID,
        version: Long,
        intent: DeclarationIntent,
    ): DeclarationRow {
        val scene = scene(meetingId)
        val declaration = declaration(scene, declarationId)
        forbidIf(declaration.userId != scene.actor)
        DeclarationRules.window(scene.meeting.status, DeclarationAct.INTENT, secretary = false)
        val state = DeclarationRules.afterIntent(declaration.state, intent)
        val event =
            DeclarationEvent(
                ids.next(),
                declaration.id,
                DeclarationEventKind.INTENT_CHANGED,
                state,
                scene.actor,
                null,
                DeclarationHow.IN_APP,
                null,
                false,
                clock.instant(),
            )
        val changed = move(declaration, version, state, intent, declaration.ruling, event)
        effects.intentChanged(scene, scene.itemOf(declaration), declaration, changed)
        return changed
    }

    /**
     * Withdraws a declaration with a reason: by the declarant, or by an officer not recused from the item. Once the
     * meeting is held only a secretary may, as a correction (one entered in error).
     */
    fun withdraw(
        meetingId: UUID,
        declarationId: UUID,
        version: Long,
        reason: String?,
    ): DeclarationRow {
        val scene = scene(meetingId)
        val declaration = declaration(scene, declarationId)
        val item = scene.itemOf(declaration)
        val own = declaration.userId == scene.actor
        val secretary = access.manages(scene.meeting.forumId)
        val correction = DeclarationRules.window(scene.meeting.status, DeclarationAct.WITHDRAW, secretary)
        if (!own || correction) requireOfficerFree(scene, item)
        val (text, problem) = DeclarationRules.reason(reason)
        requireNoProblems(listOfNotNull(problem))
        val state = DeclarationRules.afterWithdrawal(declaration.state)
        val event =
            DeclarationEvent(
                ids.next(),
                declaration.id,
                DeclarationEventKind.WITHDRAWN,
                state,
                scene.actor,
                null,
                DeclarationHow.IN_APP,
                text,
                correction,
                clock.instant(),
            )
        val changed = move(declaration, version, state, declaration.intent, declaration.ruling, event)
        effects.withdrawn(scene, item, declaration, changed, checkNotNull(text), correction)
        return changed
    }

    /** The meeting, if the actor sees it, with their relation and its items; 404 otherwise. */
    fun scene(meetingId: UUID): DeclarationScene {
        val (meeting, relation) = guard.requireVisible(meetingId)
        val actor = actorOrNull() ?: throw ForbiddenException(null)
        return DeclarationScene(meeting, relation, declarations.agenda(meeting), actor, access.role(meeting.forumId))
    }

    /**
     * [declarationId] of the scene's meeting, if the actor may see it (their own; any for an officer, the chair or the
     * vice-chair); 404 otherwise.
     */
    fun declaration(
        scene: DeclarationScene,
        declarationId: UUID,
    ): DeclarationRow =
        store
            .find(declarationId)
            ?.takeIf { it.meetingId == scene.meeting.id && (scene.officer || scene.chairSeat || it.userId == scene.actor) }
            ?: throw MeetingNotFoundException("declaration")

    /** Moves [declaration] from the state and [version] the client saw; 412 when someone else changed it first. */
    fun move(
        declaration: DeclarationRow,
        version: Long,
        state: DeclarationState,
        intent: DeclarationIntent,
        ruling: Ruling?,
        event: DeclarationEvent,
    ): DeclarationRow {
        if (declaration.version != version || !store.transition(declaration, state, intent, ruling, event)) {
            throw ConcurrentModificationException("declaration ${declaration.id}", version)
        }
        return declaration.copy(state = state, intent = intent, ruling = ruling, version = declaration.version + 1)
    }

    /** An officer acting on someone else's declaration on [item]: not when they are recused from it (403 `RECUSED`). */
    fun requireOfficerFree(
        scene: DeclarationScene,
        item: DeclarableItem,
    ) {
        if (!scene.officer) throw MeetingNotFoundException("declaration")
        if (declarations.recused(scene.actor, item)) throw RecusedException()
    }

    /**
     * The item to declare on: for oneself, one whose title the actor sees in the latest revision (officers: any item of
     * the draft too); for someone else, any item of the agenda. 404 otherwise; a break is never one.
     */
    private fun item(
        scene: DeclarationScene,
        itemId: UUID,
        forSomeone: Boolean,
    ): DeclarableItem {
        val item = scene.agenda.item(itemId, scene.officer)
        val sees =
            item != null &&
                (
                    scene.officer ||
                        (item.published != null && item.id in declarations.titles(scene.agenda, scene.actor))
                )
        if (!sees && !(forSomeone && scene.officer)) throw MeetingNotFoundException("item")
        return item ?: throw MeetingNotFoundException("item")
    }

    /** Recording for [target]: an officer not recused from the item, for someone in the audience or an officer. */
    private fun requireRecorder(
        scene: DeclarationScene,
        item: DeclarableItem,
        target: UUID,
    ) {
        forbidIf(!scene.officer)
        if (declarations.recused(scene.actor, item)) throw RecusedException()
        requireNoProblems(
            listOfNotNull(
                FieldProblem("onBehalfOf", "NOT_IN_AUDIENCE", "Only someone the meeting is for, or an officer of the committee.")
                    .takeIf { !declarations.inAudienceOrOfficer(scene.meeting, target) },
            ),
        )
    }

    private fun act(forSomeone: Boolean) = if (forSomeone) DeclarationAct.RECORD else DeclarationAct.DECLARE
}

/**
 * The chair's rulings (design E04 §1.2): the chair in office rules, or the vice-chair when the chair is the declarant,
 * is recused from the item, or there is none; a secretary may record the ruling on the chair's behalf, saying how it was
 * given. Nobody rules on their own declaration (409 `SELF_RULING`).
 */
@Service
class DeclarationRulings(
    private val service: Declarations,
    private val access: MeetingAccess,
    private val declarations: DeclarationAccess,
    private val effects: DeclarationEffects,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    fun rule(
        meetingId: UUID,
        declarationId: UUID,
        version: Long,
        input: RulingInput,
    ): DeclarationRow {
        val scene = service.scene(meetingId)
        val declaration = service.declaration(scene, declarationId)
        val item = scene.itemOf(declaration)
        refuseIf(declaration.userId == scene.actor, "SELF_RULING") { "Nobody rules on their own declaration." }
        DeclarationRules.window(scene.meeting.status, DeclarationAct.RULE, secretary = false)
        forbidIf(!(if (input.onBehalf) access.manages(scene.meeting.forumId) else scene.chairSeat))
        if (declarations.recused(scene.actor, item)) throw RecusedException()
        val ruler = declarations.ruler(scene.meeting.forumId, item, declaration.userId)
        if (input.onBehalf) {
            refuseIf(ruler == null, "NO_CHAIR") { "The committee has no chair or vice-chair in office who can rule on this." }
        } else {
            forbidIf(ruler != scene.actor)
        }
        val (note, problem) = DeclarationRules.optional("note", input.note, DeclarationRules.MAX_RULING_NOTE)
        requireNoProblems(listOfNotNull(problem, if (input.onBehalf) DeclarationRules.how(input.how) else null))
        val state = DeclarationRules.afterRuling(declaration.state, input.outcome)
        val how = if (input.onBehalf) checkNotNull(input.how) else DeclarationHow.IN_APP
        val onBehalfBy = scene.actor.takeIf { input.onBehalf }
        val now = clock.instant()
        val ruling = Ruling(input.outcome, ruler, onBehalfBy, how, note, now)
        val event =
            DeclarationEvent(ids.next(), declaration.id, DeclarationEventKind.RULED, state, ruler, onBehalfBy, how, note, false, now)
        val changed = service.move(declaration, version, state, declaration.intent, ruling, event)
        effects.ruled(scene, item, declaration, changed)
        return changed
    }
}

/**
 * What follows a declaration change (design E04 §2, §5): the audit entry, with ids and SHA-256 hashes of the nature and
 * reasons, never their text; and the notifications, to the declarant, to the officers not recused from the item, and to
 * the chair asked for a ruling. Nobody is told of their own change.
 */
@Component
class DeclarationEffects(
    private val audit: AuditLog,
    private val notifier: MeetingNotifier,
    private val declarations: DeclarationAccess,
    private val directory: ForumDirectory,
) {
    fun declared(
        scene: DeclarationScene,
        item: DeclarableItem,
        row: DeclarationRow,
        reason: String?,
    ) {
        record(
            "meeting.declaration.made",
            scene,
            row,
            null,
            mapOf(
                "intent" to row.intent.name,
                "source" to row.source.name,
                "how" to row.how.name,
                "onBehalfOf" to row.userId.toString().takeIf { row.source == DeclarationSource.ON_BEHALF },
                "recordedBy" to row.recordedBy?.toString(),
                "revision" to row.revision,
                "atMeeting" to row.atMeeting,
                "correction" to row.correction,
                "natureSha256" to DeclarationRules.sha256(row.nature),
                "reasonSha256" to reason?.let { DeclarationRules.sha256(it) },
            ),
        )
        val ruler = if (row.state == DeclarationState.AWAITING_RULING) declarations.ruler(scene.meeting.forumId, item, row.userId) else null
        if (row.source ==
            DeclarationSource.ON_BEHALF
        ) {
            tell(scene, item, NotificationKind.MEETING_DECLARATION_RECORDED, setOf(row.userId), "RECORDED")
        }
        tell(scene, item, NotificationKind.MEETING_DECLARATION_MADE, officers(scene, item) - row.userId - setOfNotNull(ruler), "DECLARED")
        ruler?.let { tell(scene, item, NotificationKind.MEETING_RULING_NEEDED, setOf(it), null) }
    }

    fun intentChanged(
        scene: DeclarationScene,
        item: DeclarableItem,
        before: DeclarationRow,
        after: DeclarationRow,
    ) {
        record(
            "meeting.declaration.intent_changed",
            scene,
            after,
            mapOf("intent" to before.intent.name, "state" to before.state.name),
            mapOf("intent" to after.intent.name, "state" to after.state.name),
        )
        if (after.state == DeclarationState.AWAITING_RULING) {
            declarations.ruler(scene.meeting.forumId, item, after.userId)?.let {
                tell(scene, item, NotificationKind.MEETING_RULING_NEEDED, setOf(it), null)
            }
        }
    }

    fun ruled(
        scene: DeclarationScene,
        item: DeclarableItem,
        before: DeclarationRow,
        after: DeclarationRow,
    ) {
        val ruling = checkNotNull(after.ruling)
        record(
            "meeting.declaration.ruled",
            scene,
            after,
            mapOf("state" to before.state.name),
            mapOf(
                "state" to after.state.name,
                "outcome" to ruling.outcome.name,
                "by" to ruling.by?.toString(),
                "onBehalfBy" to ruling.onBehalfBy?.toString(),
                "how" to ruling.how.name,
                "noteSha256" to ruling.note?.let { DeclarationRules.sha256(it) },
            ),
        )
        tell(scene, item, NotificationKind.MEETING_DECLARATION_RULED, setOf(after.userId), null, mapOf("outcome" to ruling.outcome.name))
    }

    fun withdrawn(
        scene: DeclarationScene,
        item: DeclarableItem,
        before: DeclarationRow,
        after: DeclarationRow,
        reason: String,
        correction: Boolean,
    ) {
        record(
            "meeting.declaration.withdrawn",
            scene,
            after,
            mapOf("state" to before.state.name),
            mapOf("state" to after.state.name, "reasonSha256" to DeclarationRules.sha256(reason), "correction" to correction),
        )
        tell(scene, item, NotificationKind.MEETING_DECLARATION_RECORDED, setOf(after.userId), "WITHDRAWN")
        tell(scene, item, NotificationKind.MEETING_DECLARATION_MADE, officers(scene, item) - after.userId, "WITHDRAWN")
    }

    /** The officers told of declarations on [item]: those in office and not recused from it. */
    private fun officers(
        scene: DeclarationScene,
        item: DeclarableItem,
    ): Set<UUID> {
        val officers = declarations.officers(scene.meeting.forumId)
        return officers - declarations.recusedAmong(officers, item)
    }

    private fun record(
        action: String,
        scene: DeclarationScene,
        row: DeclarationRow,
        before: Map<String, Any?>?,
        after: Map<String, Any?>,
    ) {
        val ids = mapOf("declarationId" to row.id.toString(), "itemId" to row.itemId.toString(), "userId" to row.userId.toString())
        audit.record(
            action,
            MeetingLifecycle.OBJECT_TYPE,
            scene.meeting.id.toString(),
            before = before?.let { ids + it },
            after =
                ids + after,
        )
    }

    private fun tell(
        scene: DeclarationScene,
        item: DeclarableItem,
        kind: NotificationKind,
        users: Set<UUID>,
        change: String?,
        extra: Map<String, String> = emptyMap(),
    ) {
        val forum = directory.forum(scene.meeting.forumId) ?: return
        val details = mapOf("item" to item.number.orEmpty()) + listOfNotNull(change?.let { "change" to it }).toMap() + extra
        (users - scene.actor).sorted().forEach { notifier.declaration(it, kind, scene.meeting, forum, details) }
    }
}
