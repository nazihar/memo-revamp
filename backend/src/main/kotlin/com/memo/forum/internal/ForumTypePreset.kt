package com.memo.forum.internal

import com.memo.forum.AgendaPolicy
import com.memo.forum.ForumRetention
import com.memo.forum.ForumRole
import com.memo.forum.MANAGEMENT_AGENDA
import com.memo.forum.MemberAttribute
import com.memo.forum.MinutesApproval
import com.memo.forum.MinutesPolicy
import com.memo.forum.Outcome
import com.memo.forum.OutcomeKind
import com.memo.forum.QuorumComposition
import com.memo.forum.QuorumRule
import com.memo.forum.QuorumThreshold
import com.memo.forum.STANDARD_AGENDA
import com.memo.forum.TermPolicy
import com.memo.forum.ThresholdKind

/**
 * Starting points offered when a forum type is created (ADR-0056, research §3.3): editable examples drawn from published
 * bank charters, never applied silently. Labels are English, like tenant content (Phase 3 Q3). Boards and board committees
 * approve their minutes at the next meeting, management committees by circulation (ADR-0066, recommendation 2 of
 * E06's UX review); memos move on when the meeting is held, and resolutions are numbered `BRC/2026/014` (ADR-0065).
 */
enum class ForumTypePreset(
    val displayName: String,
    val settings: ForumTypeSettings,
) {
    BOARD(
        "Board",
        ForumTypeSettings(
            name = "Board",
            description = "The board of directors.",
            retention = ForumRetention(years = 10),
            memberAttributes = listOf(INDEPENDENT, NON_EXECUTIVE),
            quorum = QuorumRule(minimum = 2, required = listOf(listOf(ForumRole.CHAIR, ForumRole.VICE_CHAIR))),
            outcomes =
                outcomes(
                    OutcomeKind.APPROVED to true,
                    OutcomeKind.APPROVED_WITH_CONDITIONS to true,
                    OutcomeKind.REJECTED to true,
                    OutcomeKind.DEFERRED to false,
                    OutcomeKind.NOTED to false,
                ),
            termPolicy = TermPolicy(termMonths = 36, maxTerms = 3),
            agenda = AgendaPolicy(sections = STANDARD_AGENDA),
        ),
    ),
    BOARD_COMMITTEE(
        "Board committee",
        ForumTypeSettings(
            name = "Board committee",
            description = "A committee of the board under its terms of reference.",
            retention = ForumRetention(years = 10),
            memberAttributes = listOf(INDEPENDENT, NON_EXECUTIVE),
            quorum =
                QuorumRule(
                    minimum = 2,
                    required = listOf(listOf(ForumRole.CHAIR, ForumRole.VICE_CHAIR)),
                    composition = listOf(QuorumComposition("independent", majority = true)),
                ),
            outcomes =
                outcomes(
                    OutcomeKind.APPROVED to true,
                    OutcomeKind.APPROVED_WITH_CONDITIONS to true,
                    OutcomeKind.REJECTED to true,
                    OutcomeKind.DEFERRED to false,
                    OutcomeKind.RETURNED to false,
                    OutcomeKind.REFERRED to false,
                    OutcomeKind.NOTED to false,
                ),
            termPolicy = TermPolicy(termMonths = 36, maxTerms = 3),
            agenda = AgendaPolicy(sections = STANDARD_AGENDA),
        ),
    ),
    MANAGEMENT_COMMITTEE(
        "Management committee",
        ForumTypeSettings(
            name = "Management committee",
            description = "A management committee such as a credit committee or ALCO.",
            retention = ForumRetention(years = 10),
            memberAttributes = listOf(MemberAttribute("management", "Management")),
            quorum =
                QuorumRule(
                    threshold = QuorumThreshold(ThresholdKind.MAJORITY),
                    minimum = 3,
                    required = listOf(listOf(ForumRole.CHAIR, ForumRole.VICE_CHAIR)),
                ),
            outcomes =
                outcomes(
                    OutcomeKind.APPROVED to true,
                    OutcomeKind.APPROVED_WITH_CONDITIONS to true,
                    OutcomeKind.REJECTED to true,
                    OutcomeKind.DEFERRED to false,
                    OutcomeKind.RETURNED to false,
                    OutcomeKind.REFERRED to false,
                    OutcomeKind.NOTED to false,
                ),
            termPolicy = TermPolicy(),
            agenda = AgendaPolicy(sections = MANAGEMENT_AGENDA),
            // Management committees meet often and need their minutes quickly: by circulation (owner, 2026-11-19).
            minutes = MinutesPolicy(approval = MinutesApproval.BY_CIRCULATION),
        ),
    ),
}

private val INDEPENDENT = MemberAttribute("independent", "Independent")
private val NON_EXECUTIVE = MemberAttribute("nonExecutive", "Non-executive")

/** The default English labels of each outcome kind. */
val OUTCOME_LABELS =
    mapOf(
        OutcomeKind.APPROVED to "Approved",
        OutcomeKind.APPROVED_WITH_CONDITIONS to "Approved with conditions",
        OutcomeKind.REJECTED to "Rejected",
        OutcomeKind.DEFERRED to "Deferred",
        OutcomeKind.RETURNED to "Returned for more information",
        OutcomeKind.REFERRED to "Referred to another committee",
        OutcomeKind.NOTED to "Noted",
        OutcomeKind.WITHDRAWN to "Withdrawn",
    )

private fun outcomes(vararg kinds: Pair<OutcomeKind, Boolean>): List<Outcome> =
    kinds.map { (kind, resolution) -> Outcome(kind, OUTCOME_LABELS.getValue(kind), resolution) }
