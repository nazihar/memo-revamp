package com.memo.workflow

import com.memo.workflow.internal.DoaMatcher
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * The delegation of authority as subjects ask about it before they start (design E04 §4.7): who would approve, and
 * whether a route reaches them. Inside the caller's tenant transaction.
 */
@Service
class WorkflowAuthority(
    private val doa: DoaMatcher,
) {
    /** Who the DoA in force would ask to approve [subjectId] if [initiator] submitted it now. */
    fun preview(
        subjectType: String,
        subjectId: UUID,
        initiator: UUID,
    ): DoaPreview = doa.preview(subjectType, subjectId, initiator)

    /** The DoA groups [route] would not give their approvals; empty when it covers them or none applies. */
    fun shortfalls(
        subjectType: String,
        subjectId: UUID,
        initiator: UUID,
        route: List<RouteEntry>,
    ): List<DoaShortfall> = doa.shortfalls(subjectType, subjectId, initiator, route)
}
