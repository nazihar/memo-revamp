package com.memo.workflow.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.WORKFLOW_SUBSTITUTION
import com.memo.org.OrgDirectory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Who acts for whom today (design E04 §4.5), as the runner reads it when it creates tasks; [Substitutions] writes them.
 * Inside a tenant transaction.
 */
@Component
class SubstitutesInForce(
    private val tenantDsl: TenantDsl,
    private val directory: OrgDirectory,
) {
    fun of(person: UUID): UUID? {
        val today = directory.today()
        return tenantDsl
            .current()
            .select(WORKFLOW_SUBSTITUTION.SUBSTITUTE_ID)
            .from(WORKFLOW_SUBSTITUTION)
            .where(
                WORKFLOW_SUBSTITUTION.PRINCIPAL_ID.eq(person),
                WORKFLOW_SUBSTITUTION.VALID_FROM.le(today),
                WORKFLOW_SUBSTITUTION.VALID_TO.isNull.or(WORKFLOW_SUBSTITUTION.VALID_TO.gt(today)),
            ).limit(1)
            .fetchOne(WORKFLOW_SUBSTITUTION.SUBSTITUTE_ID)
    }
}
