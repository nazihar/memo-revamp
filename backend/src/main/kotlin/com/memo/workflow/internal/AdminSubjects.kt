package com.memo.workflow.internal

import com.memo.common.authz.Policy
import com.memo.workflow.SubjectRef
import com.memo.workflow.WorkflowSubject
import org.springframework.stereotype.Component

/** Subjects as administrators see them in lists (design E01 §5): the title only if they are cleared for it. */
@Component
class AdminSubjects(
    private val subjects: List<WorkflowSubject>,
    private val policy: Policy,
) {
    /** One query per subject type. */
    fun of(refs: Collection<SubjectRef>): Map<SubjectRef, AdminTaskSubject> {
        val summaries =
            refs
                .groupBy { it.type }
                .flatMap { (type, items) ->
                    subjects
                        .singleOrNull { it.type == type }
                        ?.summaries(items.map { it.id })
                        .orEmpty()
                        .map { (id, summary) -> SubjectRef(type, id) to summary }
                }.toMap()
        val clearance = policy.clearanceRank()
        return refs.associateWith { ref ->
            val summary = summaries[ref]
            val restricted = summary == null || summary.classificationRank > clearance
            AdminTaskSubject(ref.type, ref.id, summary?.reference, summary?.title?.takeUnless { restricted }, restricted)
        }
    }
}
