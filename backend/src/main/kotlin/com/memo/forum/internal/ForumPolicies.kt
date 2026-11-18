package com.memo.forum.internal

import com.memo.forum.ForumDirectory
import com.memo.forum.Quorum
import com.memo.forum.QuorumSeat
import com.memo.org.OrgDirectory
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * A committee's policy as its members read it (flow K2, design E01 §3): the type's published version, which the type
 * endpoints keep to the makers and checkers of `forum.types.*`, and the quorum on a date over the members in office.
 */
@Component
class ForumPolicies(
    private val versions: ForumTypeVersionStore,
    private val directory: ForumDirectory,
    private val org: OrgDirectory,
) {
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
}
