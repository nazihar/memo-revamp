package com.memo.common.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.csrf.CsrfException
import tools.jackson.databind.json.JsonMapper
import java.net.URI

/** Writes RFC 9457 Problem Details from the security filter chain (outside Spring MVC's exception handling). */
internal fun HttpServletResponse.writeProblem(
    jsonMapper: JsonMapper,
    status: HttpStatus,
    detail: String,
    instance: String,
    type: URI? = null,
) {
    val problem =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            this.instance = URI.create(instance)
            if (type != null) this.type = type
        }
    this.status = status.value()
    contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
    jsonMapper.writeValue(outputStream, problem)
}

class ProblemAuthenticationEntryPoint(
    private val jsonMapper: JsonMapper,
) : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) = response.writeProblem(jsonMapper, HttpStatus.UNAUTHORIZED, "Authentication is required.", request.requestURI)
}

class ProblemAccessDeniedHandler(
    private val jsonMapper: JsonMapper,
) : AccessDeniedHandler {
    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        val user = SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedUser
        if (user != null && !user.sessionComplete && accessDeniedException !is CsrfException) {
            // Password accepted, TOTP (or enrolment) outstanding: the client continues the sign-in, it is not forbidden.
            response.writeProblem(
                jsonMapper,
                HttpStatus.UNAUTHORIZED,
                "A second sign-in step is required.",
                request.requestURI,
                ProblemTypes.MFA_REQUIRED,
            )
        } else {
            response.writeProblem(jsonMapper, HttpStatus.FORBIDDEN, "Access is denied.", request.requestURI)
        }
    }
}
