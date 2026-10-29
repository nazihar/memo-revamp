package com.memo.tenancy

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR-0022: which slug a host names, per mode. */
class TenantResolverTest {
    private fun resolver(props: TenancyProperties) = TenantResolver(props, mockk(relaxed = true))

    private val multi = resolver(TenancyProperties(baseDomain = "memo.example", fallbackTenant = ""))

    @Test
    fun `multi mode takes the single label in front of the base domain`() {
        assertThat(multi.slugFor("acme.memo.example")).isEqualTo("acme")
        assertThat(multi.slugFor("ACME.Memo.Example:443")).isEqualTo("acme")
        assertThat(multi.slugFor("acme.memo.example.")).isEqualTo("acme")
    }

    @Test
    fun `hosts outside the base domain, nested labels and invalid slugs name no tenant`() {
        assertThat(multi.slugFor("acme.other.example")).isNull()
        assertThat(multi.slugFor("a.b.memo.example")).isNull()
        assertThat(multi.slugFor("-bad.memo.example")).isNull()
        assertThat(multi.slugFor("memo.example")).isNull()
        assertThat(multi.slugFor("10.0.0.5")).isNull()
    }

    @Test
    fun `the bare base domain maps to the fallback tenant when one is configured`() {
        val dev = resolver(TenancyProperties(baseDomain = "dev.memo.example", fallbackTenant = "demo"))
        assertThat(dev.slugFor("dev.memo.example")).isEqualTo("demo")
        assertThat(dev.slugFor("acme.dev.memo.example")).isEqualTo("acme")
    }

    @Test
    fun `single mode maps every host to the configured tenant`() {
        val single = resolver(TenancyProperties(mode = TenancyProperties.Mode.SINGLE, singleTenant = "bank"))
        assertThat(single.slugFor("anything.example")).isEqualTo("bank")
        assertThat(single.slugFor("10.0.0.5:8080")).isEqualTo("bank")
    }
}
