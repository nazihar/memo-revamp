package com.memo.authz

import com.memo.authz.internal.AccessInterceptor
import com.memo.authz.internal.DeclaredAccess
import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.authz.PublicEndpoint
import com.memo.common.authz.RequiresPermission
import com.memo.common.authz.Resource
import com.memo.common.authz.ScopedPermission
import com.memo.common.authz.SignedIn
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.Condition
import org.jooq.Field
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.method.HandlerMethod
import java.util.UUID

/** Deny by default at the handler (ADR-0027; test plan T1). */
class AccessInterceptorTest {
    @Suppress("unused")
    class Handlers {
        fun undeclared() = Unit

        @PublicEndpoint
        fun public() = Unit

        @RequiresPermission(Permission.AUDIT_READ)
        fun audit() = Unit

        @PublicEndpoint
        @SignedIn
        fun ambiguous() = Unit
    }

    @SignedIn
    @Suppress("unused")
    class SignedInHandlers {
        fun inherited() = Unit

        @RequiresPermission(Permission.ORG_MANAGE)
        fun overridden() = Unit
    }

    private class FixedPolicy(
        private val held: Set<Permission>,
    ) : Policy {
        override fun allows(
            permission: Permission,
            resource: Resource,
        ) = permission in held

        override fun holdsAnywhere(permission: Permission) = permission in held

        override fun condition(
            permission: Permission,
            unit: Field<UUID?>?,
            rank: Field<Int?>?,
        ): Condition = DSL.falseCondition()

        override fun grants() = held.map { ScopedPermission(it, null) }

        override fun clearanceRank() = 0

        override fun isPrivileged(user: UUID) = false
    }

    private fun handler(
        bean: Any,
        name: String,
    ) = HandlerMethod(bean, bean.javaClass.getMethod(name))

    private fun pass(
        policy: Policy,
        handler: HandlerMethod,
    ) = AccessInterceptor(policy).preHandle(MockHttpServletRequest(), MockHttpServletResponse(), handler)

    @Test
    fun `a handler without an access declaration is refused`() {
        assertThatThrownBy { pass(FixedPolicy(Permission.entries.toSet()), handler(Handlers(), "undeclared")) }
            .isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `two declarations on one handler count as none`() {
        assertThat(DeclaredAccess.of(handler(Handlers(), "ambiguous"))).isNull()
    }

    @Test
    fun `a required permission is checked against the policy`() {
        assertThat(pass(FixedPolicy(setOf(Permission.AUDIT_READ)), handler(Handlers(), "audit"))).isTrue()
        assertThatThrownBy { pass(FixedPolicy(emptySet()), handler(Handlers(), "audit")) }
            .isInstanceOf(ForbiddenException::class.java)
            .extracting("permission")
            .isEqualTo(Permission.AUDIT_READ)
    }

    @Test
    fun `public handlers pass, and a class declaration applies unless the method has its own`() {
        assertThat(pass(FixedPolicy(emptySet()), handler(Handlers(), "public"))).isTrue()
        assertThat(DeclaredAccess.of(handler(SignedInHandlers(), "inherited"))).isEqualTo(DeclaredAccess.Signed)
        assertThat(DeclaredAccess.of(handler(SignedInHandlers(), "overridden"))).isInstanceOf(DeclaredAccess.Permission::class.java)
    }
}
