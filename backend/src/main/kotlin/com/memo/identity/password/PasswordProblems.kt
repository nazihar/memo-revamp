package com.memo.identity.password

import com.memo.common.web.FieldProblem

/** Policy violations as field problems (`errors[].code` is the violation; the message is the English fallback). */
object PasswordProblems {
    fun of(
        field: String,
        violations: List<PasswordViolation>,
    ): List<FieldProblem> = violations.map { FieldProblem(field, it.name, message(it)) }

    fun message(violation: PasswordViolation): String =
        when (violation) {
            PasswordViolation.TOO_SHORT -> "The password is too short."
            PasswordViolation.TOO_LONG -> "The password is too long."
            PasswordViolation.BREACHED -> "This password appears in known data breaches. Choose another."
            PasswordViolation.CONTAINS_IDENTITY -> "The password must not contain your email or name."
        }
}
