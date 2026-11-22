package com.memo.search.internal.decision

import com.memo.common.crypto.DataKeys
import com.memo.common.web.FieldProblem
import com.memo.search.DecisionCursor
import com.memo.search.internal.SearchRejectedException
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

/**
 * The register's cursors, sealed (design E08 §1.4; E08 T1's note for T2): a cursor holds the last row's keyset values,
 * among them the decision time of a restricted line, which the viewer is never shown. So the values are encrypted with
 * AES-256-GCM under the installation's data key ([DataKeys]) and bound, as associated data, to the tenant, the viewer and a
 * fingerprint of the search (words, filters, order): a client can neither read a cursor nor craft or alter one, and a
 * cursor of another search, person or tenant does not open. Anything that does not open is 422 `cursor INVALID`.
 */
@Component
class RegisterCursors(
    private val keys: DataKeys,
    private val json: JsonMapper,
) {
    /** [cursor] as an opaque token, bound to [binding] (tenant, viewer and the search's fingerprint). */
    fun seal(
        cursor: DecisionCursor,
        binding: String,
    ): String {
        val values =
            mapOf(
                "d" to cursor.decisionId.toString(),
                "m" to cursor.meetingDate?.toString(),
                "a" to cursor.decidedAt?.toString(),
                "s" to cursor.score?.toString(),
                "p" to cursor.period,
                "n" to cursor.sequence?.toString(),
            ).filterValues { it != null }
        val sealed = keys.encrypt(json.writeValueAsBytes(values), AAD + binding)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sealed)
    }

    /** The cursor [token] holds, when it was sealed for [binding]; otherwise 422 `cursor INVALID`. */
    fun open(
        token: String,
        binding: String,
    ): DecisionCursor =
        runCatching {
            require(token.length <= MAX_TOKEN) { "cursor too long" }
            val node = json.readTree(keys.decrypt(Base64.getUrlDecoder().decode(token), AAD + binding))
            DecisionCursor(
                decisionId = UUID.fromString(node.text("d")),
                meetingDate = node.text("m")?.let(LocalDate::parse),
                decidedAt = node.text("a")?.let(Instant::parse),
                score = node.text("s")?.toFloat(),
                period = node.text("p"),
                sequence = node.text("n")?.toLong(),
            )
        }.getOrElse { throw SearchRejectedException(listOf(INVALID)) }

    private fun JsonNode.text(name: String): String? = get(name)?.takeUnless { it.isNull }?.asString()

    private companion object {
        const val AAD = "memo/decision-register/cursor/v1|"
        const val MAX_TOKEN = 1024
        val INVALID = FieldProblem("cursor", "INVALID", "Not a cursor of this search. Start the search again.")
    }
}
