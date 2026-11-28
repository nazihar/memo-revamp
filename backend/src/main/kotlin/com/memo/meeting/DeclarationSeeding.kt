package com.memo.meeting

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.meeting.internal.AgendaGuard
import com.memo.meeting.internal.DeclarationReviews
import com.memo.meeting.internal.DeclarationStore
import com.memo.meeting.internal.Declarations
import com.memo.meeting.internal.DeclareInput
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Declarations of interest for seeders (the demo tenant, Phase 4 E04 T9), through the same services as the API, as the
 * user bound to the current unit of work: declaring for oneself, recording one for someone, and confirming the review.
 * Never called from requests. Each call is its own tenant transaction. The seeder decides what already exists ("seeders
 * only fill gaps"): [held] answers whether a person ever had a declaration on an item.
 */
@Service
class DeclarationSeeding(
    private val declarations: Declarations,
    private val reviews: DeclarationReviews,
    private val store: DeclarationStore,
    private val guard: AgendaGuard,
    private val transactions: TenantTransactions,
) {
    /** The live agenda item of [meetingId] titled [title], if there is one. */
    fun item(
        meetingId: UUID,
        title: String,
    ): UUID? =
        tx {
            guard
                .rows(meetingId)
                ?.items
                ?.firstOrNull { it.title == title }
                ?.id
        }

    /** Whether [userId] ever held a declaration on [itemId] of [meetingId], withdrawn ones included. */
    fun held(
        meetingId: UUID,
        itemId: UUID,
        userId: UUID,
    ): Boolean = tx { store.ofMeeting(meetingId, userId).any { it.itemId == itemId } }

    /** The bound person declares [nature] on [itemId]; the declaration's id. */
    fun declare(
        meetingId: UUID,
        itemId: UUID,
        nature: String,
        intent: DeclarationIntent,
    ): UUID = tx { declarations.declare(meetingId, itemId, DeclareInput(nature, intent)).id }

    /** The bound officer records [userId]'s declaration on [itemId], given [how] ([howNote]: e.g. the email's date). */
    fun record(
        meetingId: UUID,
        itemId: UUID,
        userId: UUID,
        nature: String,
        intent: DeclarationIntent,
        how: DeclarationHow,
        howNote: String?,
    ): UUID =
        tx { declarations.declare(meetingId, itemId, DeclareInput(nature, intent, onBehalfOf = userId, how = how, howNote = howNote)).id }

    /** Whether the bound user has confirmed the review of any revision of [meetingId]. */
    fun reviewed(meetingId: UUID): Boolean = tx { reviews.mine(meetingId).reviewedRevision != null }

    /** The bound person confirms "I have declared my interests" for the latest revision, unless they had; whether now. */
    fun confirmReview(meetingId: UUID): Boolean =
        tx {
            val mine = reviews.mine(meetingId)
            if (mine.latestRevision == 0 || mine.reviewedRevision == mine.latestRevision) {
                false
            } else {
                reviews.review(meetingId, mine.latestRevision)
                true
            }
        }

    private fun <T> tx(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)
}
