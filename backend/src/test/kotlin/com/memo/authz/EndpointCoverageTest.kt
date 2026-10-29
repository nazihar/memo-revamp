package com.memo.authz

import com.memo.authz.internal.DeclaredAccess
import com.memo.testsupport.AuthzMatrix
import com.memo.testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/**
 * Deny by default, enforced by the build (TESTING_STRATEGY §3.4, ADR-0027): every API endpoint declares its access and
 * appears in an authorisation matrix file, and the matrix lists no endpoint that does not exist.
 */
class EndpointCoverageTest : IntegrationTest() {
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    lateinit var mapping: RequestMappingHandlerMapping

    private val api: Map<String, HandlerMethod> by lazy {
        mapping.handlerMethods
            .flatMap { (info, handler) ->
                val methods = info.methodsCondition.methods.map { it.name }
                info.patternValues.filter { it.startsWith("/api/") }.flatMap { path -> methods.map { "$it $path" to handler } }
            }.toMap()
    }

    @Test
    fun `every API endpoint is in an authorisation matrix`() {
        assertThat(api).isNotEmpty()
        assertThat(api.keys - AuthzMatrix.endpoints).describedAs("endpoints without a matrix entry").isEmpty()
    }

    @Test
    fun `every matrix entry is a real endpoint`() {
        assertThat(AuthzMatrix.endpoints - api.keys).describedAs("matrix entries without an endpoint").isEmpty()
    }

    @Test
    fun `every API handler declares exactly one access rule`() {
        assertThat(api.filterValues { DeclaredAccess.of(it) == null }.keys).describedAs("handlers without an access declaration").isEmpty()
    }
}
