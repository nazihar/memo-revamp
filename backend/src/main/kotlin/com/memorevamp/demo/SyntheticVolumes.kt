package com.memorevamp.demo

import com.memorevamp.common.tenant.TenantId
import com.memorevamp.common.tenant.TenantTransactions
import com.memorevamp.tenancy.TenantRegistry
import com.memorevamp.tenancy.TenantSettings
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component

/**
 * Target volumes for performance environments (TESTING_STRATEGY §7). Phase 0 can only generate tenants and settings;
 * every later phase adds its aggregates here (users and org tree in 1b, memos with history in 3, …) so the same generator
 * feeds Gatling runs. Final figures come from the confirmed NFRs (docs/plan/nfr.md).
 */
data class VolumeSpec(
    val tenants: Int,
    val settingsPerTenant: Int,
) {
    init {
        require(tenants in 1..MAX_TENANTS) { "tenants must be 1..$MAX_TENANTS" }
        require(settingsPerTenant in 0..MAX_SETTINGS) { "settingsPerTenant must be 0..$MAX_SETTINGS" }
    }

    private companion object {
        const val MAX_TENANTS = 10_000
        const val MAX_SETTINGS = 1_000
    }
}

/** Deterministic, synthetic data only (TESTING_STRATEGY §1.8); refuses to run under the `prod` profile. */
@Component
class SyntheticVolumeGenerator(
    private val registry: TenantRegistry,
    private val settings: TenantSettings,
    private val transactions: TenantTransactions,
    private val environment: Environment,
) {
    fun generate(
        spec: VolumeSpec,
        runId: String,
    ): List<TenantId> {
        check(!environment.acceptsProfiles(Profiles.of("prod"))) { "Synthetic volumes must never be generated in production" }
        require(RUN_ID.matches(runId)) { "runId must match ${RUN_ID.pattern}" }
        return (1..spec.tenants).map { n ->
            val tenant = registry.create(slug = "synthetic-$runId-$n", name = "Synthetic tenant $n ($runId)").id
            transactions.inTenant(tenant) {
                repeat(spec.settingsPerTenant) { i -> settings.put("synthetic.setting$i", "value-$n-$i") }
            }
            tenant
        }
    }

    private companion object {
        val RUN_ID = Regex("^[a-z0-9]{1,20}$")
    }
}
