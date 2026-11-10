package com.memo.common.authz

import java.util.UUID

/** A classification level as other modules need it: its rank orders it (0 is the lowest), its name labels it. */
data class ClassificationLevelRef(
    val id: UUID,
    val rank: Int,
    val name: String,
)

/**
 * Classification levels and the clearance of users other than the actor (ADR-0027), for checks the [Policy] cannot make
 * because they are about someone else: a memo's route must name people cleared for it (design E01 §1). Implemented by
 * the authz module from the cached grants. Calls run inside a tenant transaction.
 */
interface Clearances {
    /** Each user's clearance rank (0: the lowest level, also for users without a clearance). */
    fun rankOf(users: Collection<UUID>): Map<UUID, Int>

    /** The tenant's levels, lowest first. */
    fun levels(): List<ClassificationLevelRef>

    /** The level [id], or null when the tenant has no such level. */
    fun level(id: UUID): ClassificationLevelRef? = levels().firstOrNull { it.id == id }

    /** The lowest level (rank 0); every tenant has one from provisioning. */
    fun lowest(): ClassificationLevelRef = levels().first()
}
