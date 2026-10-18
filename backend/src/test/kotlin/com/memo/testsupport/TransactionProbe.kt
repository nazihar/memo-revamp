package com.memo.testsupport

import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantId
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** Test-only bean with declarative transactions, to prove the tenant reaches `@Transactional` code (ADR-0017). */
@Component
class TransactionProbe(
    private val dsl: DSLContext,
    private val tenantDsl: TenantDsl,
) {
    @Transactional
    fun databaseTenant(): String? = setting()

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun databaseTenantInNewTransaction(): String? = setting()

    @Transactional
    fun outerThenInner(inner: () -> String?): Pair<String?, String?> = setting() to inner()

    @Transactional
    fun tenantDslTenant(): TenantId = tenantDsl.tenant()

    @Transactional
    fun tenantRows(table: String): Long = dsl.fetchValue("select count(*) from $table") as Long

    private fun setting(): String? = (dsl.fetchValue("select current_setting('app.tenant_id', true)") as String?)?.ifEmpty { null }
}
