package com.memorevamp

import com.tngtech.archunit.core.domain.JavaClass
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ModularityTest {
    /**
     * `com.memorevamp.shared` is the KMP library from `/shared` (a dependency, not an application module), so it is
     * excluded from module detection; any module may use it. Test-only helper packages are excluded as well.
     */
    private val modules =
        ApplicationModules.of(
            MemoApplication::class.java,
            JavaClass.Predicates.resideInAnyPackage(
                "com.memorevamp.shared..",
                // test-only packages
                "com.memorevamp.testsupport..",
                "com.memorevamp.contract..",
            ),
        )

    @Test
    fun `all planned modules exist`() {
        assertThat(modules.map { it.identifier.toString() })
            .containsExactlyInAnyOrder(
                "tenancy",
                "identity",
                "org",
                "authz",
                "audit",
                "files",
                "workflow",
                "memo",
                "forum",
                "meeting",
                "book",
                "voting",
                "minutes",
                "actions",
                "signature",
                "notification",
                "search",
                "system",
                "demo",
                "common",
            )
    }

    @Test
    fun `module boundaries are respected`() {
        modules.verify()
    }
}
