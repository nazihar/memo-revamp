package com.memo.actions.internal

import com.memo.forum.DueChange
import com.memo.meeting.Meetings
import com.memo.org.OrgDirectory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** What a viewer may do with an action now (design E07 §4), for the action's page; the services decide again on each call. */
@Component
class ActionAbilityRules(
    private val deciders: ActionDeciders,
    private val org: OrgDirectory,
    private val meetings: Meetings,
    private val reports: ActionReportStore,
    private val clock: Clock,
) {
    /** What [viewer] may do with [row] now, by the matrix of design E07 §4 (the services decide again on each call). */
    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    fun of(
        viewer: ActionViewer,
        row: ActionRow,
        recused: Boolean,
        contributors: List<UUID>,
        mode: DueChange,
        pending: DueRequestRow?,
        today: LocalDate,
    ): ActionAbilities {
        val forum = row.forumId
        val manages = viewer.manages(forum) && !recused
        val secretary = viewer.secretary(forum) && !recused
        val officer = (viewer.secretary(forum) || viewer.chairs(forum)) && !recused
        val owners =
            setOfNotNull(row.owner?.userId, row.holderUserId.takeIf { row.status != ActionStatus.DRAFT })
        val owner = viewer.userId in owners || row.owner?.positionId?.let { it in viewer.positions } == true
        val contributor = viewer.userId in contributors && !recused
        val draft = row.status == ActionStatus.DRAFT
        val open = row.status in ActionChecks.OPEN
        val underway = row.status in ActionChecks.UNDERWAY
        val ended = row.status in ActionEnding.ENDED && row.cancelKind != CancelKind.DISCARDED
        val recent =
            row.closedAt?.let { ChronoUnit.DAYS.between(it.atZone(org.zone()).toLocalDate(), today) <= ActionEnding.REOPEN_DAYS } == true
        return ActionAbilities(
            edit = manages && (draft || open),
            issue = manages && draft,
            discard = manages && draft,
            // A secretary writes for the owner ([updateOnBehalf]); their own "Post an update" would be refused (E07 T12).
            // Once marked done the owner's step is over (design E07 §1.4, §1.7): the action waits for its verifier, its date
            // no longer drives its colour and a date request would be withdrawn, so updates and new dates are not offered.
            update = underway && (owner || contributor),
            updateOnBehalf = underway && secretary,
            confidence = underway && (owner || secretary),
            start = row.status == ActionStatus.OPEN && owner,
            requestDue = underway && owner && mode != DueChange.OWNER_WITH_REASON && pending == null,
            decideDue = open && pending != null && !recused && viewer.userId in deciders.of(row, mode),
            changeDue = open && (manages || (owner && mode == DueChange.OWNER_WITH_REASON)),
            overrideRag = row.status in ActionChecks.UNDERWAY && officer,
            changeOwner = open && manages,
            cancel = open && officer,
            acceptRisk = open && officer && viewer.chairs(forum),
            reopen = ended && recent && manages,
            markDone = row.status in ActionChecks.UNDERWAY && viewer.userId == row.holderUserId,
            verify = row.status == ActionStatus.DONE_PENDING && !owner && !contributor && !recused && row.id in viewer.tasks(),
            holdBack = manages && holdable(row),
        )
    }

    /**
     * Whether [row] may be held back from the next report ([ActionReportHolds.hold]'s rule, for a secretary or
     * `forums.manage` not recused): issued, and the committee has a meeting still to come whose reports do not yet leave it
     * out (409 `NO_MEETING` and `ALREADY_HELD` otherwise).
     */
    private fun holdable(row: ActionRow): Boolean {
        val next = row.reference?.let { ActionMeetings.next(meetings.ofForum(row.forumId), clock.instant()) }
        return next != null && row.id !in reports.held(next.id)
    }
}
