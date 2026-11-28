package com.memo.forum

import com.memo.common.events.TenantScopedEvent
import java.util.UUID

/**
 * A forum or its membership changed (ADR-0056): published in the changing transaction by every change to the forum, to its
 * terms of office, and to the holder of a position it seats. The meeting module recomputes the audience of the forum's open
 * meetings (design E03 §1.6).
 */
data class ForumChanged(
    override val tenantId: UUID,
    val forumId: UUID,
) : TenantScopedEvent

/** A forum-type version was published (ADR-0056): forums of the type follow it from their next meeting. */
data class ForumTypePublished(
    override val tenantId: UUID,
    val typeId: UUID,
    val version: Int,
) : TenantScopedEvent

/**
 * A submission changed (ADR-0059), with its [status] after the change. The meeting module keeps agenda items in step:
 * a submission returned, declined, referred, deferred or withdrawn leaves the draft agenda (design E03 §1.8).
 */
data class ForumSubmissionChanged(
    override val tenantId: UUID,
    val submissionId: UUID,
    val status: SubmissionStatus,
) : TenantScopedEvent
