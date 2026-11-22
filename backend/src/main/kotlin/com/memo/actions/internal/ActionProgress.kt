package com.memo.actions.internal

import com.memo.actions.internal.ActionStore.Column
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.id.IdGenerator
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.files.Files
import com.memo.forum.ActionsPolicy
import com.memo.forum.ForumPolicies
import com.memo.org.OrgDirectory
import com.memo.workflow.WorkingDays
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/** A progress update (design E07 §1.6, `POST /actions/{id}/updates`). */
data class UpdateInput(
    val text: String?,
    /** The owner's word (the owner, or a secretary writing for them). */
    val confidence: Confidence? = null,
    /** Evidence: `action.evidence` files uploaded for this action by the person writing, up to 10. */
    val fileIds: List<UUID> = emptyList(),
    /** A secretary writing for the owner names them, and how they heard. */
    val onBehalfOf: UUID? = null,
    val how: How? = null,
    /** The earlier update this one corrects (its number on the timeline). */
    val corrects: Int? = null,
)

/** Who writes for whom: the owner themselves, a contributor, or a secretary for the owner. */
data class OnBehalf(
    val onBehalfOf: UUID? = null,
    val how: How? = null,
)

/**
 * Progress on an issued action (ADR-0067, design E07 §1.5, §1.6): updates by the owner (the person, the position's holder,
 * or the holder of the owner's task), a contributor, or a secretary not recused writing for the owner with how they heard;
 * the owner's confidence; evidence files; corrections as new entries; "I have started"; and the red-amber-green override
 * by a secretary or the chair not recused, with a reason, cleared by the next update unless it has a date. Every change
 * locks the action, is a guarded update on its version (If-Match) and its status, writes an entry and is audited.
 */
@Service
class ActionProgress(
    private val store: ActionStore,
    private val lines: ActionLines,
    private val scenes: ActionScenes,
    private val files: Files,
    private val policies: ForumPolicies,
    private val workingDays: WorkingDays,
    private val org: OrgDirectory,
    private val effects: ActionProgressEffects,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    /** Posts [input] on [actionId], seen at [version]; returns the entry's number. */
    fun update(
        actionId: UUID,
        version: Long,
        input: UpdateInput,
    ): Int {
        val scene = scenes.scene(actionId)
        val row = scene.row
        ActionChecks.requireOpen(row.status, "Updates are posted on an action that is open.")
        val forBehalf = writer(scene, OnBehalf(input.onBehalfOf, input.how))
        val text = input.text?.trim().orEmpty()
        val corrected = input.corrects?.let { lines.entryOf(row.id, it) }
        requireNoProblems(
            listOfNotNull(
                FieldProblem("text", "REQUIRED", "Say how it is going.").takeIf { text.isEmpty() },
                FieldProblem("text", "LENGTH", "At most $TEXT_MAX characters.").takeIf { text.length > TEXT_MAX },
                FieldProblem("confidence", "CONFIDENCE", "Only the owner says how it is going.")
                    .takeIf { input.confidence != null && !scene.owner && forBehalf == null },
                FieldProblem("corrects", "CORRECTS", "You can correct only an update you posted.")
                    .takeIf { input.corrects != null && (corrected?.kind != UPDATE || corrected.actorId != scene.viewer.userId) },
            ) + evidence(row.id, scene.viewer.userId, input.fileIds),
        )
        val now = now()
        val today = org.today()
        val policy = policy(row)
        val clears = row.ragOverride != null && row.ragOverrideUntil == null
        val values =
            mutableMapOf<Column, Any?>(
                Column.LAST_UPDATE_AT to now.atOffset(ZoneOffset.UTC),
                Column.STALE_FROM to staleFrom(today, policy),
            )
        if (row.status == ActionStatus.OPEN) values[Column.STATUS] = ActionStatus.IN_PROGRESS.name
        input.confidence?.let { values[Column.CONFIDENCE] = it.name }
        if (clears) values.putAll(CLEAR_OVERRIDE)
        guarded(row, version, ActionChecks.OPEN, values)
        val more = EntryMore(forBehalf?.onBehalfOf, forBehalf?.how, text, input.confidence, input.fileIds.distinct(), input.corrects)
        val seq = lines.entry(row.id, UPDATE, scene.viewer.userId, mapOf("ragCleared" to clears.takeIf { it }), now, more)
        effects.updated(row, seq, more, clears)
        return seq
    }

    /** "I have started" (OPEN → IN_PROGRESS without text) on [actionId], seen at [version]. */
    fun started(
        actionId: UUID,
        version: Long,
        on: OnBehalf,
    ) {
        val scene = scenes.scene(actionId)
        val row = scene.row
        refuseIf(row.status != ActionStatus.OPEN, ActionRules.WRONG_STATE) { "Only an action not yet started can be started." }
        val forBehalf = writer(scene, on, contributors = false)
        guarded(row, version, setOf(ActionStatus.OPEN), mapOf(Column.STATUS to ActionStatus.IN_PROGRESS.name))
        val more = EntryMore(forBehalf?.onBehalfOf, forBehalf?.how)
        lines.entry(row.id, "STARTED", scene.viewer.userId, emptyMap(), now(), more)
        effects.started(row, more)
    }

    /** Overrides [actionId]'s colour with [colour] for [reason], until [until] (null: until the next update). */
    fun override(
        actionId: UUID,
        version: Long,
        colour: RagColour?,
        reason: String?,
        until: LocalDate?,
    ) {
        val scene = scenes.scene(actionId)
        val row = scene.row
        scenes.requireChairOrSecretary(scene)
        refuseIf(
            row.status !in ActionChecks.UNDERWAY,
            ActionRules.WRONG_STATE,
        ) { "Only an open action not yet done shows a colour to set." }
        val trimmed = reason?.trim()
        requireNoProblems(
            listOfNotNull(
                FieldProblem("rag", "REQUIRED", "Choose red, amber or green.").takeIf { colour == null || colour !in RagRule.OVERRIDES },
                ActionChecks.reason(trimmed),
                FieldProblem("until", "DUE_IN_PAST", "Please enter a date on or after today.")
                    .takeIf { until != null && until < org.today() },
            ),
        )
        val values =
            mapOf<Column, Any?>(
                Column.RAG_OVERRIDE to checkNotNull(colour).name,
                Column.RAG_OVERRIDE_REASON to trimmed,
                Column.RAG_OVERRIDE_BY to scene.viewer.userId,
                Column.RAG_OVERRIDE_UNTIL to until,
            )
        guarded(row, version, ActionChecks.UNDERWAY, values)
        val details = mapOf("rag" to colour.name, "until" to until?.toString())
        val seq = lines.entry(row.id, "RAG_OVERRIDDEN", scene.viewer.userId, details, now(), EntryMore(text = trimmed))
        effects.overridden(row, seq, colour, until, checkNotNull(trimmed))
    }

    /** Clears [actionId]'s override (`DELETE /actions/{id}/rag`), seen at [version]. */
    fun clear(
        actionId: UUID,
        version: Long,
    ) {
        val scene = scenes.scene(actionId)
        val row = scene.row
        scenes.requireChairOrSecretary(scene)
        refuseIf(row.ragOverride == null, NO_OVERRIDE) { "The action's colour is not overridden." }
        guarded(row, version, ActionChecks.ISSUED, CLEAR_OVERRIDE)
        val seq = lines.entry(row.id, "RAG_CLEARED", scene.viewer.userId, emptyMap(), now())
        effects.cleared(row, seq, "OFFICER")
    }

    /**
     * Who writes and for whom: the owner (or, with [contributors], a contributor) themselves, or a secretary not recused
     * writing for the owner with how they heard; 403 otherwise, 403 `RECUSED` for a recused contributor or secretary.
     */
    private fun writer(
        scene: ActionScene,
        on: OnBehalf,
        contributors: Boolean = true,
    ): OnBehalf? {
        val row = scene.row
        if (on.onBehalfOf == null && on.how == null) {
            forbidUnless(scene.owner || (contributors && scene.contributor))
            // The owner may be recused from the item and still own its action; a contributor may not (design E07 §1.4).
            if (!scene.owner) scenes.requireNotRecused(scene)
            return null
        }
        forbidUnless(scene.viewer.secretary(row.forumId))
        scenes.requireNotRecused(scene)
        requireNoProblems(
            listOfNotNull(
                FieldProblem("onBehalfOf", "REQUIRED", "Name the owner you write for.").takeIf { on.onBehalfOf == null },
                FieldProblem("onBehalfOf", "ON_BEHALF_OF", "You write only for the action's owner.")
                    .takeIf { on.onBehalfOf != null && on.onBehalfOf !in scenes.owners(row) },
                FieldProblem("how", "REQUIRED", "Say how you heard from the owner.").takeIf { on.how == null },
            ),
        )
        return on
    }

    /** The evidence files' problems: at most 10, each an `action.evidence` file of this action uploaded by [user]. */
    private fun evidence(
        actionId: UUID,
        user: UUID,
        fileIds: List<UUID>,
    ): List<FieldProblem> {
        if (fileIds.size > ActionEvidencePurpose.MAX_FILES) {
            return listOf(FieldProblem("fileIds", "TOO_MANY", "At most ${ActionEvidencePurpose.MAX_FILES} files."))
        }
        val current = files.currentVersions(fileIds)
        return fileIds.mapIndexedNotNull { i, id ->
            val info = files.info(id)
            FieldProblem("fileIds/$i", "unknown", "No such evidence file for this action.").takeIf {
                info == null || info.purpose != ActionEvidencePurpose.KEY || info.ownerId != actionId.toString() ||
                    info.createdBy != user || id !in current
            }
        }
    }

    private fun guarded(
        row: ActionRow,
        version: Long,
        from: Collection<ActionStatus>,
        values: Map<Column, Any?>,
    ) {
        if (version != row.version || !store.change(row.id, version, from, values)) {
            throw ConcurrentModificationException("action ${row.id}", version)
        }
    }

    private fun policy(row: ActionRow): ActionsPolicy = policies.current(row.forumId)?.actions ?: ActionsPolicy()

    private fun staleFrom(
        today: LocalDate,
        policy: ActionsPolicy,
    ): LocalDate? = policy.staleWorkingDays.takeIf { it != ActionsPolicy.OFF }?.let { workingDays.after(today, it) }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    companion object {
        const val UPDATE = "UPDATE"
        const val TEXT_MAX = 4000
        const val NO_OVERRIDE = "NO_OVERRIDE"

        val CLEAR_OVERRIDE: Map<Column, Any?> =
            mapOf(
                Column.RAG_OVERRIDE to null,
                Column.RAG_OVERRIDE_REASON to null,
                Column.RAG_OVERRIDE_BY to null,
                Column.RAG_OVERRIDE_UNTIL to null,
            )
    }
}

/** The checks the changes of an issued action share (design E07 §1.4). */
object ActionChecks {
    /** Issued and not finished: updated, re-dated, re-owned, edited and cancelled. */
    val OPEN = setOf(ActionStatus.OPEN, ActionStatus.IN_PROGRESS, ActionStatus.DONE_PENDING)

    /** Open and not yet done: shows red, amber or green. */
    val UNDERWAY = setOf(ActionStatus.OPEN, ActionStatus.IN_PROGRESS)

    /** Every status but a draft's. */
    val ISSUED = ActionStatus.entries.toSet() - ActionStatus.DRAFT

    /** 409 `WRONG_STATE` unless [status] is open. */
    fun requireOpen(
        status: ActionStatus,
        message: String,
    ) = refuseIf(status !in OPEN, ActionRules.WRONG_STATE) { message }

    /** A reason, 1 to 500 characters, at [field]. */
    fun reason(
        text: String?,
        field: String = "reason",
        required: Boolean = true,
    ): FieldProblem? =
        when {
            text.isNullOrBlank() -> FieldProblem(field, "REQUIRED", "Give a reason.").takeIf { required }
            text.trim().length > ActionRules.REASON_MAX -> FieldProblem(field, "LENGTH", "At most ${ActionRules.REASON_MAX} characters.")
            else -> null
        }
}
