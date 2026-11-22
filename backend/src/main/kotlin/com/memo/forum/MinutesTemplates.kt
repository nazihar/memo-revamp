package com.memo.forum

import com.memo.templating.TagVocabulary
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

/**
 * The tags a minutes template may use (design E06 §1.7), with what each stands for, as the committee type's Minutes tab
 * lists them. Values are pre-formatted text in the tenant's language and the meeting's zone. Each agenda item is an
 * element of the [ITEMS] block: `{{?items}}…{{/items}}` repeats per item, each in its own locked content control
 * (ADR-0066), so its two tags must each be alone in a paragraph of the body.
 */
object MinutesTemplateTags {
    const val ITEMS = "items"

    /** The meeting's values, usable anywhere (also inside the items block). Each may open a block shown when not blank. */
    val MEETING: Map<String, String> =
        linkedMapOf(
            "meeting.committee" to "Committee",
            "meeting.label" to "Meeting (for example BRC 3/2026)",
            "meeting.kind" to "Ordinary or special meeting",
            "meeting.date" to "Date",
            "meeting.start" to "Start time",
            "meeting.end" to "End time",
            "meeting.zone" to "Time zone",
            "meeting.place" to "Place",
            "meeting.mode" to "In person, online or both",
            "meeting.chair" to "Who chaired the meeting",
            "meeting.closedAt" to "When the meeting closed",
            "minutes.status" to "Deprecated, always empty: the copies and the record are stamped instead",
            "minutes.approvedOn" to "Deprecated, always empty: the record's cover and stamp say when",
            "minutes.approvedBy" to "Deprecated, always empty: the record's cover and stamp say who",
            "quorum.opening" to "The quorum when the meeting opened (a sentence)",
            "actions.count" to "How many actions the meeting agreed on no item (empty when none)",
        )

    /** Table-row loops with their row fields: `{{present}}` in a table row, `[name]` in the row below. */
    val LOOPS: Map<String, Map<String, String>> =
        linkedMapOf(
            "present" to
                linkedMapOf(
                    "name" to "Name",
                    "role" to "Role",
                    "mode" to "In person or online",
                    "from" to "Joined at",
                    "until" to "Left at",
                    "note" to "Note",
                ),
            "alternates" to linkedMapOf("name" to "Alternate", "forMember" to "Standing in for", "mode" to "In person or online"),
            "inAttendance" to linkedMapOf("name" to "Name", "capacity" to "Capacity", "items" to "Items attended"),
            "apologies" to linkedMapOf("name" to "Name", "alternate" to "Represented by"),
            "absent" to linkedMapOf("name" to "Name"),
            "quorumChanges" to linkedMapOf("at" to "Time", "item" to "During item", "text" to "What happened"),
            "resolutions" to linkedMapOf("number" to "Resolution number", "item" to "Item", "outcome" to "Outcome", "text" to "Text"),
            "actions" to ACTION_FIELDS + ("item" to "Item (always empty: an item's actions are in its block)"),
        )

    /** The actions agreed on an item (E07, design E07 §11): a row loop inside the items block. */
    const val ITEM_ACTIONS = "item.actions"

    /** Each item's values, inside the items block only. Each may open a block shown when not blank. */
    val ITEM: Map<String, String> =
        linkedMapOf(
            "item.number" to "Item number",
            "item.title" to "Item title",
            "item.section" to "Section of the agenda",
            "item.purpose" to "For decision, discussion or noting",
            "item.presenters" to "Presenters",
            "item.papers" to "Papers, circulated in advance or tabled",
            "item.late" to "Why a paper was late",
            "item.declarations" to "Declarations of interest (sentences)",
            "item.notes" to "Key points discussed",
            "item.outcome" to "Outcome (in capitals)",
            "item.resolutionNumber" to "Resolution number",
            "item.resolutionText" to "Resolution text",
            "item.conditions" to "Conditions (numbered lines)",
            "item.validUntil" to "Valid until",
            "item.vote" to "Vote (empty when decided by consensus)",
            "item.dissent" to "Dissent recorded",
            "item.abstentions" to "Abstentions",
            "item.tookNoPart" to "Members who took no part",
            "item.quorum" to "Quorum for the item (a sentence)",
            "item.ratification" to "Decided without a quorum, or ratifying an earlier decision",
            "item.arrivals" to "Who joined during the item",
            "item.departures" to "Who left during the item",
            "item.actionCount" to "How many actions were agreed on the item (empty when none)",
        )

    /** The row fields of an action, in the meeting's `actions` loop and the item's `item.actions` loop (E07). */
    val ACTION_FIELDS: Map<String, String>
        get() =
            linkedMapOf(
                "reference" to "Action reference",
                "action" to "What is to be done",
                "owner" to "Owner",
                "due" to "Due date",
            )

    /** Names admins commonly type, and the tag each means: they only lead suggestions ("Did you mean {{item.notes}}?"). */
    val ALIASES: Map<String, String> =
        mapOf(
            "item.discussion" to "item.notes",
            "item.keyPoints" to "item.notes",
            "item.minutes" to "item.notes",
            "item.decision" to "item.outcome",
            "item.resolution" to "item.resolutionText",
            "item.heading" to "item.title",
            "item.name" to "item.title",
            "item.interests" to "item.declarations",
            "item.voting" to "item.vote",
            "meeting.name" to "meeting.label",
            "meeting.title" to "meeting.label",
            "meeting.number" to "meeting.label",
            "meeting.time" to "meeting.start",
            "meeting.venue" to "meeting.place",
            "meeting.location" to "meeting.place",
            "meeting.chairman" to "meeting.chair",
            "meeting.chairperson" to "meeting.chair",
            "minutes.approved" to "minutes.approvedOn",
            "attendees" to "present",
            "attendance" to "present",
        )

    /**
     * Tags kept for templates already uploaded but no longer recommended (E06 T7): the minutes are never filled again once
     * drafted, so a status or approval line in the document would stay as it was drafted, and the approved, hashed document
     * would still say DRAFT. They are filled empty; a template that uses one gets a warning, not a problem.
     */
    val DEPRECATED: Map<String, String> =
        listOf("minutes.status", "minutes.approvedOn", "minutes.approvedBy").associateWith {
            "No longer needed and always empty: every page of a reader's draft copy is stamped DRAFT, and the approved " +
                "record's cover and the stamp on each of its pages say who approved the minutes and when. Remove it from " +
                "the template."
        }

    val vocabulary: TagVocabulary =
        TagVocabulary(
            values = MEETING.keys,
            sections = MEETING.keys,
            loops = LOOPS.mapValues { it.value.keys },
            blocks =
                mapOf(
                    ITEMS to TagVocabulary(values = ITEM.keys, sections = ITEM.keys, loops = mapOf(ITEM_ACTIONS to ACTION_FIELDS.keys)),
                ),
            aliases = ALIASES,
            deprecated = DEPRECATED,
        )
}

/** The languages the built-in minutes template comes in: English, and Arabic (right to left). */
enum class MinutesLanguage(
    val code: String,
) {
    EN("en"),
    AR("ar"),
}

/**
 * The built-in minutes template (design E06 §1.7), used by a committee type without an uploaded one: shipped with the
 * application in English and Arabic, and checked against [MinutesTemplateTags] by a test like any upload.
 */
@Component
class MinutesTemplates {
    /** The built-in template's .docx in [language]. */
    fun builtIn(language: MinutesLanguage = MinutesLanguage.EN): ByteArray =
        ClassPathResource(resource(language)).inputStream.use { it.readAllBytes() }

    companion object {
        /** The file purpose of an uploaded minutes template (the forum module owns such files). */
        const val PURPOSE = "forum.minutes-template"

        fun resource(language: MinutesLanguage) = "forum/minutes-template-${language.code}.docx"
    }
}
