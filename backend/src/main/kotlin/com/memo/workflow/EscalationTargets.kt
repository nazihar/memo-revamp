package com.memo.workflow

/** A forum an `escalate-to-forum` step can name: its key, and its name and code for the builder's picker. */
data class EscalationTarget(
    val key: String,
    val name: String,
    val code: String,
)

/**
 * The forums `escalate-to-forum` steps may name (ADR-0056), implemented by the forum module, which depends on this one.
 * With no implementation every key is accepted, as before Phase 4. Inside a tenant transaction.
 */
interface EscalationTargets {
    /** Which of [keys] are active forums. */
    fun active(keys: Set<String>): Set<String>

    /** Every active forum, by name. */
    fun list(): List<EscalationTarget>
}
