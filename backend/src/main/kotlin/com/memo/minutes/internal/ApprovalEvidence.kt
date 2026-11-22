package com.memo.minutes.internal

import com.memo.common.authz.StepUpCheck
import com.memo.common.context.ActorContext
import com.memo.identity.AuthenticationContext
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat

/** ADR-0051's evidence of one authenticated act: how the actor was authenticated, and from where. */
data class Evidence(
    /** The factors and their times, the step-up's time and age, the session's hash. */
    val authentication: JsonNode,
    /** IP address and user agent. */
    val client: JsonNode,
)

/**
 * ADR-0051's evidence of the signed-in actor's authenticated act, for the minutes' approvals (the chair's confirmation and
 * their approval of a correction) and a secretary's certification of a resolution extract (design E06 §1.6.4–§1.6.6): the
 * session's factors and times, the step-up the act needed and its age at [Evidence] time, the session's hash, the IP
 * address and user agent; and the SHA-256 of canonical JSON (keys sorted) that the records chain or certify.
 */
@Component
class ApprovalEvidence(
    private val authentication: AuthenticationContext,
    private val stepUp: StepUpCheck,
    json: JsonMapper,
) {
    private val plain: JsonMapper = json
    private val canonical: JsonMapper = json.rebuild().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build()

    /** The actor's evidence now, for an act at [at] that needed a fresh step-up. */
    fun of(at: Instant): Evidence {
        val evidence = authentication.current()
        val proven = stepUp.provenAt()
        val auth =
            linkedMapOf(
                "factors" to evidence?.factors.orEmpty(),
                "authenticatedAt" to evidence?.authenticatedAt?.toString(),
                "passwordAt" to evidence?.passwordAt?.toString(),
                "secondFactorAt" to evidence?.secondFactorAt?.toString(),
                "stepUpRequired" to true,
                "stepUpAt" to proven?.toString(),
                "stepUpAgeSeconds" to proven?.let { Duration.between(it, at).seconds.coerceAtLeast(0) },
                "sessionHash" to evidence?.sessionHash,
            )
        val client = ActorContext.current().client.let { linkedMapOf("ip" to it?.ip, "userAgent" to it?.userAgent) }
        return Evidence(plain.valueToTree(auth), plain.valueToTree(client))
    }

    /** [value] as a JSON tree (to store). */
    fun tree(value: Any): JsonNode = plain.valueToTree(value)

    /** [node] as a map, for canonical hashing. */
    fun mapOf(node: JsonNode): Map<*, *> = canonical.treeToValue(node, Map::class.java)

    /** SHA-256 (hex) of [fields] as canonical JSON, followed by [suffix]. */
    fun hash(
        fields: Map<String, Any?>,
        suffix: String = "",
    ): String = sha256(canonical.writeValueAsString(fields) + suffix)

    /** The authentication's factors as recorded (`PASSWORD`, `TOTP`). */
    fun factors(authentication: JsonNode): List<String> =
        authentication["factors"]?.let { f -> (0 until f.size()).map { f.get(it).asString() } }.orEmpty()

    companion object {
        fun sha256(text: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
    }
}
