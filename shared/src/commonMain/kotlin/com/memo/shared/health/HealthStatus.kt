package com.memo.shared.health

/** Overall platform status, shared by the backend and (later) mobile clients. */
enum class HealthStatus {
    UP,
    DEGRADED,
    DOWN,
    ;

    companion object {
        /** The most severe of the given statuses; [UP] when there are none. */
        fun worstOf(statuses: Iterable<HealthStatus>): HealthStatus = statuses.maxByOrNull { it.ordinal } ?: UP
    }
}
