package com.memo.forum

import io.swagger.v3.oas.annotations.media.Schema

/** A section of an agenda (ADR-0060): ordinary business, or consent items taken together without discussion. */
enum class AgendaSectionKind { STANDARD, CONSENT }

/**
 * Standing business on every agenda (ADR-0060), which later epics fill: apologies (E05), declarations of interest (E04),
 * the minutes of the last meeting (E06), matters arising (E07), and any other business.
 */
enum class StandingKind { APOLOGIES, DECLARATIONS, PREVIOUS_MINUTES, MATTERS_ARISING, AOB }

/** An item of the template: standing business when [standingKind] is set, an ordinary item otherwise. */
data class AgendaTemplateItem(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val title: String,
    val standingKind: StandingKind? = null,
    val purpose: SubmissionPurpose = SubmissionPurpose.INFORMATION,
    /** Planned minutes; null: no time of its own. */
    val minutes: Int? = null,
)

/** A section of the template; at most one [receivesSubmissions] (where items placed from the tray land). */
data class AgendaTemplateSection(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val title: String,
    val kind: AgendaSectionKind = AgendaSectionKind.STANDARD,
    /** In camera: for the chair, vice-chair, members and secretaries only. */
    val membersOnly: Boolean = false,
    val receivesSubmissions: Boolean = false,
    val items: List<AgendaTemplateItem> = emptyList(),
)

/**
 * A committee type's agenda policy (ADR-0060, design E03 §1.7, flow R): whether the chair agrees the agenda before it is
 * first published, and the standard agenda new meetings start from. Part of each type version and reviewed with it;
 * meetings already started keep their own agenda.
 */
data class AgendaPolicy(
    val chairAgreement: Boolean = true,
    val sections: List<AgendaTemplateSection> = STANDARD_AGENDA,
)

/** The caps of an agenda (design E03 §1.2): for templates here, and for every meeting's agenda in the meeting module. */
object AgendaLimits {
    const val MAX_SECTIONS = 30
    const val MAX_ITEMS = 150
    const val MAX_SECTION_TITLE = 200
    const val MAX_ITEM_TITLE = 300
    const val MAX_MINUTES = 600
}

private const val SHORT = 5
private const val LONGER = 10

/** The standard agenda of a board or board committee (research E03 §3.2; flow R). */
val STANDARD_AGENDA: List<AgendaTemplateSection> =
    listOf(
        AgendaTemplateSection(
            "Opening",
            items =
                listOf(
                    AgendaTemplateItem("Welcome and apologies", StandingKind.APOLOGIES, SubmissionPurpose.INFORMATION, SHORT),
                    AgendaTemplateItem("Declarations of interest", StandingKind.DECLARATIONS, SubmissionPurpose.INFORMATION, SHORT),
                    AgendaTemplateItem("Minutes of the last meeting", StandingKind.PREVIOUS_MINUTES, SubmissionPurpose.DECISION, SHORT),
                    AgendaTemplateItem("Matters arising and actions", StandingKind.MATTERS_ARISING, SubmissionPurpose.INFORMATION, LONGER),
                ),
        ),
        AgendaTemplateSection("For decision", receivesSubmissions = true),
        AgendaTemplateSection("Consent items", kind = AgendaSectionKind.CONSENT),
        AgendaTemplateSection("For noting"),
        AgendaTemplateSection(
            "Any other business",
            items =
                listOf(
                    AgendaTemplateItem("Any other business (notified in advance)", StandingKind.AOB, SubmissionPurpose.INFORMATION, SHORT),
                ),
        ),
    )

/** The standard agenda of a management committee: the same, without a consent section. */
val MANAGEMENT_AGENDA: List<AgendaTemplateSection> = STANDARD_AGENDA.filter { it.kind != AgendaSectionKind.CONSENT }
