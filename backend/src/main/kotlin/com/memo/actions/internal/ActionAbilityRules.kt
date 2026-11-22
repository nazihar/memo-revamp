package com.memo.actions.internal

import com.memo.forum.DueChange
import com.memo.org.OrgDirectory
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** What a viewer may do with an action now (design E07 §4), for the action's page; the services decide again on each call. */
@Component
class ActionAbilityRules(
    private val deciders: ActionDeciders,
    private val org: OrgDirectory,
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
        val ended = row.status in ActionEnding.ENDED && row.cancelKind != CancelKind.DISCARDED
        val recent =
            row.closedAt?.let { ChronoUnit.DAYS.between(it.atZone(org.zone()).toLocalDate(), today) <= ActionEnding.REOPEN_DAYS } == true
        return ActionAbilities(
            edit = manages && (draft || open),
            issue = manages && draft,
            discard = manages && draft,
            update = open && (owner || contributor || secretary),
            updateOnBehalf = open && secretary,
            confidence = open && (owner || secretary),
            start = row.status == ActionStatus.OPEN && (owner || secretary),
            requestDue = open && owner && mode != DueChange.OWNER_WITH_REASON && pending == null,
            decideDue = open && pending != null && !recused && viewer.userId in deciders.of(row, mode),
            changeDue = open && (manages || (owner && mode == DueChange.OWNER_WITH_REASON)),
            overrideRag = row.status in ActionChecks.UNDERWAY && officer,
            changeOwner = open && manages,
            cancel = open && officer,
            acceptRisk = open && officer && viewer.chairs(forum),
            reopen = ended && recent && manages,
        )
    }
}
