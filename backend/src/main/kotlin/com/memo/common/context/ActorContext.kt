package com.memo.common.context

import java.util.UUID

/** Who performs the current unit of work; recorded on every audit entry (AGENTS.md §3.3). */
sealed interface Actor {
    /** Stable type label used in audit records. */
    val type: String

    /** Actor id in audit records, or null for anonymous requests. */
    val id: String?

    /** A signed-in user, optionally acting for another user under a delegation (Phase 1b). */
    data class User(
        val userId: UUID,
        val actingAs: UUID? = null,
    ) : Actor {
        override val type = "USER"
        override val id = userId.toString()
    }

    /** The system itself: a background job, an event listener, a seeder. [name] says which. */
    data class System(
        val name: String,
    ) : Actor {
        init {
            require(name.isNotBlank()) { "system actor needs a name" }
        }

        override val type = "SYSTEM"
        override val id = name
    }

    /** A platform operator (E07); never acts inside a tenant's data. */
    data class PlatformOperator(
        val operatorId: UUID,
    ) : Actor {
        override val type = "PLATFORM_OPERATOR"
        override val id = operatorId.toString()
    }

    /** An unauthenticated request (for example a failed sign-in). */
    data object Anonymous : Actor {
        override val type = "ANONYMOUS"
        override val id: String? = null
    }
}

/** Where a request came from. Never contains request content. */
data class ClientInfo(
    val ip: String?,
    val userAgent: String?,
) {
    companion object {
        private const val MAX_IP = 64
        private const val MAX_USER_AGENT = 256

        fun of(
            ip: String?,
            userAgent: String?,
        ) = ClientInfo(ip?.take(MAX_IP), userAgent?.take(MAX_USER_AGENT))
    }
}

/** The actor and client bound to the current unit of work. */
data class ActorScope(
    val actor: Actor,
    val client: ClientInfo? = null,
)

/**
 * The actor of the current unit of work (ADR-0017), bound by every entry point: the request filter, job and event helpers,
 * seeders. Code that records audit entries requires it; there is no implicit default.
 */
object ActorContext {
    private val ACTOR: ScopedValue<ActorScope> = ScopedValue.newInstance()

    fun currentOrNull(): ActorScope? = if (ACTOR.isBound) ACTOR.get() else null

    fun current(): ActorScope = currentOrNull() ?: throw IllegalStateException("No actor bound to the current unit of work")

    fun <T> call(
        scope: ActorScope,
        block: () -> T,
    ): T = ScopedValue.where(ACTOR, scope).call(ScopedValue.CallableOp<T, RuntimeException> { block() })

    fun <T> asSystem(
        name: String,
        block: () -> T,
    ): T = call(ActorScope(Actor.System(name), currentOrNull()?.client), block)
}
