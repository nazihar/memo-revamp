package com.memo.meeting.internal

import com.memo.forum.SubmissionPurpose
import com.memo.meeting.LineAccess
import com.memo.meeting.PaperAccess
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

/** A late item, or a late paper of an item, waiting for the viewer's decision. */
data class LateRequestResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val itemId: UUID,
    /** Set for a late paper on an item already on the agenda. */
    val paperId: UUID?,
    /** The item's number as it would be published; null for an item that is itself waiting. */
    val number: String?,
    /** The item's title; null when the viewer's line of it shows no title ("Restricted item", the paper rule). */
    val itemTitle: String?,
    /** The paper's title; null for an item, or when the viewer may not read the paper (the paper rule). */
    val paperTitle: String?,
    /** Only when the viewer sees the item in full. */
    val purpose: SubmissionPurpose?,
)

/** The late requests the viewer decides on a meeting (design E04 §1.7 as built, "the decider's late requests"). */
data class LateRequestsResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingId: UUID,
    /** The last day papers were on time, in the meeting's zone. */
    val paperDeadline: LocalDate?,
    /** In agenda order; empty for anyone who decides none. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val requests: List<LateRequestResponse>,
    /** The agenda's version: send it as If-Match with a decision. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val version: Long,
)

/**
 * The late items and papers a person decides (design E04 §1.7 as built): the chair, or the vice-chair on the items the
 * chair is recused from. The vice-chair is a member of the meeting while a chair is in office and cannot read the draft,
 * so this lists, for anyone who sees the meeting, only the requests they decide, each as the paper rule lets them read it
 * on the draft (`PaperAccess.draft`: the title on their line, the paper's title only when they may read the paper); and
 * the decision is the chair's own (`AgendaChair`: the decider check, recusal 403 `RECUSED`), answered with this list
 * instead of the draft. Nothing is listed once the agenda no longer changes.
 */
@Service
class LateRequests(
    private val meetingGuard: MeetingGuard,
    private val guard: AgendaGuard,
    private val chair: AgendaChair,
    private val declarations: DeclarationAccess,
    private val viewers: PaperViewers,
    private val paperAccess: PaperAccess,
    private val lateness: AgendaLateness,
) {
    /** The requests the actor decides on [meetingId]; 404 when they do not see the meeting. */
    fun mine(meetingId: UUID): LateRequestsResponse {
        val (meeting, _) = meetingGuard.requireVisible(meetingId)
        val rows = guard.rows(meeting.id)
        return LateRequestsResponse(
            meetingId = meeting.id,
            paperDeadline = lateness.deadline(meeting),
            requests = rows?.let { requests(meeting, it) }.orEmpty(),
            version = rows?.agenda?.version ?: 0,
        )
    }

    /**
     * The actor's own answer on the late item [itemId], or on its late paper [paperId] (404 when the paper is not the
     * item's), at the agenda's [version]: only the decider for the item (403 otherwise; 403 `RECUSED` for a recused chair).
     */
    fun decide(
        meetingId: UUID,
        itemId: UUID,
        paperId: UUID?,
        agree: Boolean,
        version: Long,
    ) {
        if (paperId == null) {
            chair.decideItem(meetingId, itemId, version, LateDecision(agree))
            return
        }
        val paper = guard.rows(meetingId)?.papers?.firstOrNull { it.id == paperId }
        if (paper != null && paper.itemId != itemId) throw MeetingNotFoundException("paper")
        chair.decidePaper(meetingId, paperId, version, LateDecision(agree))
    }

    private fun requests(
        meeting: MeetingRow,
        rows: AgendaRows,
    ): List<LateRequestResponse> {
        val all = if (AgendaChanges.of(meeting.status) == AgendaChanges.NONE) emptyList() else waiting(rows)
        val deciding = declarations.decidedBy(meeting.forumId, all.map { it.first }.distinct())
        val mine = all.filter { (item, _) -> item.id in deciding }
        val viewer = viewers.actor(meeting)
        if (mine.isEmpty() || viewer == null) return emptyList()
        val seen = paperAccess.draft(viewer, meeting.id)
        val numbers = AgendaRules.numbers(rows.sections, rows.items)
        return mine.map { (item, paper) ->
            val line = seen.lines[item.id]
            LateRequestResponse(
                itemId = item.id,
                paperId = paper?.id,
                number = numbers[item.id],
                itemTitle = item.title.takeIf { line == LineAccess.FULL || line == LineAccess.TITLE },
                paperTitle = paper?.title?.takeIf { paper.id in seen.papers },
                purpose = item.purpose.takeIf { line == LineAccess.FULL },
            )
        }
    }

    companion object {
        /** The draft's items and papers waiting for the chair, in agenda order: an item alone, or a paper with its item. */
        fun waiting(rows: AgendaRows): List<Pair<ItemRow, PaperRow?>> =
            AgendaRules.ordered(rows.sections, rows.items).flatMap { (top, subs) -> listOf(top) + subs }.flatMap { item ->
                val papers = rows.papers.filter { it.itemId == item.id && it.late == Lateness.AWAITING_CHAIR }.sortedBy { it.position }
                listOfNotNull(item.takeIf { it.late == Lateness.AWAITING_CHAIR }?.let { it to null }) + papers.map { item to it }
            }
    }
}
