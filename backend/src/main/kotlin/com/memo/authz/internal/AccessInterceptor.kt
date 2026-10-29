package com.memo.authz.internal

import com.memo.common.authz.AuthenticationFlow
import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Policy
import com.memo.common.authz.PublicEndpoint
import com.memo.common.authz.RequiresPermission
import com.memo.common.authz.SignedIn
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** How a handler declares its access (ADR-0027): exactly one of these on the method or, failing that, its class. */
sealed interface DeclaredAccess {
    data object Public : DeclaredAccess

    data object Flow : DeclaredAccess

    data object Signed : DeclaredAccess

    data class Permission(
        val annotation: RequiresPermission,
    ) : DeclaredAccess

    companion object {
        private val TYPES =
            listOf(PublicEndpoint::class.java, AuthenticationFlow::class.java, SignedIn::class.java, RequiresPermission::class.java)

        /** The handler's declaration; null when it has none or more than one (both are build failures). */
        fun of(handler: HandlerMethod): DeclaredAccess? = on(handler.method) ?: on(handler.beanType)

        private fun on(element: java.lang.reflect.AnnotatedElement): DeclaredAccess? {
            val present = TYPES.mapNotNull { AnnotatedElementUtils.findMergedAnnotation(element, it) }
            if (present.size != 1) return null
            return when (val annotation = present.single()) {
                is PublicEndpoint -> Public
                is AuthenticationFlow -> Flow
                is SignedIn -> Signed
                is RequiresPermission -> Permission(annotation)
                else -> null
            }
        }
    }
}

/**
 * Deny by default at the handler (ADR-0027): a handler without an access declaration is refused, and
 * [RequiresPermission] is checked against the policy. Spring Security has already authenticated the request
 * (complete session outside the public and sign-in endpoints); object-level checks happen in the services.
 */
class AccessInterceptor(
    private val policy: Policy,
) : HandlerInterceptor {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (handler !is HandlerMethod) return true
        when (val access = DeclaredAccess.of(handler)) {
            null -> {
                log.error("Handler {} declares no access rule: refused", handler.shortLogMessage)
                throw ForbiddenException(null)
            }

            is DeclaredAccess.Permission -> {
                if (!policy.holdsAnywhere(access.annotation.value)) throw ForbiddenException(access.annotation.value)
            }

            DeclaredAccess.Public, DeclaredAccess.Flow, DeclaredAccess.Signed -> {}
        }
        return true
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class AccessInterceptorConfig(
    private val policy: Policy,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(AccessInterceptor(policy)).addPathPatterns("/api/**")
    }
}
