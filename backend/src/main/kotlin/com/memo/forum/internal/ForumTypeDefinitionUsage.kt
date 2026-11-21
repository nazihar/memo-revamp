package com.memo.forum.internal

import com.memo.workflow.WorkflowDefinitionUsage
import org.springframework.stereotype.Component

/**
 * A workflow definition a committee type's actions policy names (design E07 §1.13), in its published version or the one
 * being written or reviewed, cannot be retired: actions issued for the committee could not start otherwise. Inside the
 * caller's tenant transaction.
 */
@Component
class ForumTypeDefinitionUsage(
    private val versions: ForumTypeVersionStore,
) : WorkflowDefinitionUsage {
    override fun inUse(definitionKey: String): Boolean = versions.current().any { it.settings.actions.definitionKey == definitionKey }
}
