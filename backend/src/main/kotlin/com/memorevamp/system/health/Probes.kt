package com.memorevamp.system.health

/** Status of one dependency as shown publicly. [DISABLED] = not configured in this deployment (e.g. the `core` dev profile). */
enum class ComponentStatus { UP, DOWN, DISABLED }

/**
 * Reachability check for one dependency. Implementations must:
 *  - never throw for an unreachable dependency (return [ComponentStatus.DOWN]);
 *  - bound every network call with a timeout;
 *  - never expose details: the public API shows only [name] and the status.
 */
interface ComponentProbe {
    /** Stable public name; part of the API contract. */
    val name: String

    /** True for the component without which the platform cannot serve anything (only the database). */
    val critical: Boolean get() = false

    fun check(): ComponentStatus
}
