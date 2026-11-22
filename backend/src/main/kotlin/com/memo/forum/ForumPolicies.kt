package com.memo.forum

import com.memo.forum.internal.ForumNotFoundException
import com.memo.forum.internal.ForumPolicyResponse
import com.memo.forum.internal.ForumRow
import com.memo.forum.internal.ForumStore
import com.memo.forum.internal.ForumTypeVersionRow
import com.memo.forum.internal.ForumTypeVersionStore
import com.memo.org.OrgDirectory
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/**
 * A forum type version's policies as other modules apply them (design E03 §2): a meeting pins the version published
 * when it was created, and its submission, quorum and agenda policy apply to it whatever is published later.
 */
data class ForumTypePolicy(
    val typeId: UUID,
    val version: Int,
    val name: String,
    /** The classification a forum of the type has unless it sets its own. */
    val defaultClassificationId: UUID?,
    val quorum: QuorumRule,
    val submission: SubmissionPolicy,
    /** Invitations, replies and the quorum override (design E05 §1.8). */
    val meetings: MeetingPolicy,
    /** Minutes approval, when memos move on, resolution numbers and the template (design E06 §1.8). */
    val minutes: MinutesPolicy,
    val outcomes: List<Outcome>,
    /** The agenda template and the chair's agreement (ADR-0060). */
    val agenda: AgendaPolicy,
    /** Action items (design E07 §1.13): read from the version in force today ([ForumPolicies.current]), not a pinned one. */
    val actions: ActionsPolicy,
    /** How long the meeting records of the type are kept (ADR-0056): the minutes' record is locked that long (E06 T7). */
    val retention: ForumRetention? = null,
)

/**
 * A committee's policies (ADR-0056, design E03 §2): a type version's, for the meeting module, and the published one as the
 * committee's members read it (flow K2, design E01 §3), with the quorum on a date over the members in office. Inside a
 * tenant transaction.
 */
@Component
class ForumPolicies(
    private val versions: ForumTypeVersionStore,
    private val directory: ForumDirectory,
    private val org: OrgDirectory,
    private val forums: ForumStore,
) {
    /** Version [number] of [typeId], in any state; null when there is no such version. */
    fun version(
        typeId: UUID,
        number: Int,
    ): ForumTypePolicy? = versions.version(typeId, number)?.policy()

    /** The version of [typeId] published now; null when none is. */
    fun published(typeId: UUID): ForumTypePolicy? = versions.published(typeId)?.policy()

    /**
     * The policy in force today for the committee [forumId]: its type's published version, whatever version its meetings
     * pinned. Action items read their policy here (design E07 §1.13): an action lives across meetings, and its rules follow
     * the committee's current ones. Null when there is no such committee or its type has no published version.
     */
    fun current(forumId: UUID): ForumTypePolicy? = forums.find(forumId)?.let { published(it.typeId) }

    /** The type's published policy, and the quorum on [on] (default today) over the members in office that day. */
    fun policy(
        forum: ForumRow,
        on: LocalDate?,
    ): ForumPolicyResponse {
        val published = versions.published(forum.typeId) ?: throw ForumNotFoundException("forum type version")
        val settings = published.settings
        val seats = directory.membersOn(forum.id, on ?: org.today()).map { QuorumSeat(it.memberId, it.role, it.voting, it.attributes) }
        val quorum = Quorum.evaluate(settings.quorum, seats, emptyMap())
        return ForumPolicyResponse(
            typeId = forum.typeId,
            typeName = settings.name,
            version = published.number,
            description = settings.description,
            defaultClassificationId = settings.defaultClassificationId,
            memberAttributes = settings.memberAttributes,
            quorum = settings.quorum,
            outcomes = settings.outcomes,
            termPolicy = settings.termPolicy,
            submissionPolicy = settings.submissionPolicy,
            quorumBasis = quorum.basis,
            quorumNeeded = quorum.needed,
        )
    }

    private fun ForumTypeVersionRow.policy() =
        ForumTypePolicy(
            typeId = typeId,
            version = number,
            name = settings.name,
            defaultClassificationId = settings.defaultClassificationId,
            quorum = settings.quorum,
            submission = settings.submissionPolicy,
            meetings = settings.meetings,
            minutes = settings.minutes,
            outcomes = settings.outcomes,
            agenda = settings.agenda,
            actions = settings.actions,
            retention = settings.retention,
        )
}
