package com.memo.forum.internal

import com.memo.audit.AuditLog
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.review.ReviewState
import com.memo.common.web.FieldProblem
import com.memo.forum.AgendaPolicy
import org.springframework.stereotype.Service
import java.util.UUID

/** A forum type with its published and open versions, for the list and the type page. */
data class ForumTypeSummary(
    val type: ForumType,
    val published: ForumTypeVersionRow?,
    val open: ForumTypeVersionRow?,
)

/**
 * Forum types (ADR-0056, design E01 §3): created from a preset or blank as draft version 1, switched on and off directly,
 * never deleted. Versions change through [ForumTypeLifecycle]. Inside a tenant transaction; callers check permissions.
 */
@Service
class ForumTypes(
    private val store: ForumTypeStore,
    private val versions: ForumTypeVersionStore,
    private val checks: ForumTypeChecks,
    private val audit: AuditLog,
) {
    fun list(): List<ForumTypeSummary> {
        val current = versions.current().groupBy { it.typeId }
        return store.all().map { type ->
            val rows = current[type.id].orEmpty()
            ForumTypeSummary(type, rows.firstOrNull { it.state == ReviewState.PUBLISHED }, rows.firstOrNull { it.state.open })
        }
    }

    fun get(id: UUID): ForumTypeSummary = list().firstOrNull { it.type.id == id } ?: throw ForumNotFoundException("forum type")

    /** The published policy of [typeId], if it has one. */
    fun published(typeId: UUID): ForumTypeVersionRow? = versions.published(typeId)

    /**
     * Creates a type with draft version 1 from [preset] (blank: a name, majority quorum and "Approved"). Used by the API
     * (with [publish] false) and by seeders, which publish version 1 at once.
     */
    fun create(
        code: String,
        name: String,
        preset: ForumTypePreset?,
        publish: Boolean = false,
    ): ForumTypeSummary {
        val trimmed = code.trim()
        requireNoProblems(
            listOfNotNull(
                FieldProblem(
                    "code",
                    "FORMAT",
                    "1 to 10 capital letters, digits or hyphens.",
                ).takeUnless { ForumTypeChecks.CODE.matches(trimmed) },
                FieldProblem("code", "CODE_TAKEN", "Another committee type has this code.").takeIf { store.codeTaken(trimmed) },
            ),
        )
        val base = preset?.settings ?: BLANK
        val settings = base.copy(name = name.trim())
        checks.require(settings)
        val id = store.insertType(trimmed, actorOrNull())
        val state = if (publish) ReviewState.PUBLISHED else ReviewState.DRAFT
        val number = versions.insertVersion(id, state, settings, actorOrNull())
        if (publish) store.setPublished(id, number)
        audit.record(
            "forum.type.created",
            OBJECT_TYPE,
            id.toString(),
            after = mapOf("code" to trimmed, "preset" to preset?.name, "state" to state.name) + audited(settings),
        )
        return get(id)
    }

    fun setActive(
        id: UUID,
        active: Boolean,
    ): ForumTypeSummary {
        val type = store.find(id) ?: throw ForumNotFoundException("forum type")
        if (type.active != active) {
            if (!store.setActive(id, active)) throw ConcurrentModificationException("forum type $id", type.version)
            audit.record(if (active) "forum.type.activated" else "forum.type.deactivated", OBJECT_TYPE, id.toString())
        }
        return get(id)
    }

    companion object {
        const val OBJECT_TYPE = "forum_type"

        private val BLANK =
            ForumTypeSettings(
                name = "",
                outcomes = listOf(com.memo.forum.Outcome(com.memo.forum.OutcomeKind.APPROVED, "Approved", true)),
            )

        /** The agenda policy in one line: the chair's agreement, then each section with its flags and items. */
        fun agendaSummary(policy: AgendaPolicy): String =
            "chairAgreement=${policy.chairAgreement}; " +
                policy.sections.joinToString(" | ") { s ->
                    val flags =
                        listOfNotNull(s.kind.name, "membersOnly".takeIf { s.membersOnly }, "receives".takeIf { s.receivesSubmissions })
                    "${s.title} [${flags.joinToString(",")}]: " +
                        s.items.joinToString("; ") { "${it.title} (${it.standingKind ?: "ITEM"}, ${it.purpose}, ${it.minutes ?: "-"})" }
                }

        /** What the audit log records of a version: every policy setting, compactly. */
        fun audited(settings: ForumTypeSettings): Map<String, Any?> =
            mapOf(
                "name" to settings.name,
                "defaultClassificationId" to settings.defaultClassificationId?.toString(),
                "retention" to settings.retention?.let { "${it.years}y from ${it.from}" },
                "memberAttributes" to settings.memberAttributes.map { it.key },
                "quorum" to settings.quorum.toString(),
                "outcomes" to settings.outcomes.map { "${it.kind}:${it.label}${if (it.resolution) "#" else ""}" },
                "termPolicy" to settings.termPolicy.toString(),
                "submissionPolicy" to settings.submissionPolicy.toString(),
                "agenda" to agendaSummary(settings.agenda),
                "meetings" to settings.meetings.toString(),
            )
    }
}
