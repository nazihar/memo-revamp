package com.memo.minutes.internal

import com.memo.templating.FoundControl
import java.util.UUID

/** An agenda item the minutes must carry: one locked content control tagged with its id (ADR-0066). */
data class MinutedItem(
    val id: UUID,
    val number: String,
)

/** What is wrong with an item's control in a saved draft (design E06 §1.6.1). */
enum class StructureProblemKind {
    /** No control for the item: deleted (refused by ONLYOFFICE and Word, but possible), or the item came later. */
    MISSING,

    /** More than one control for the item. */
    DUPLICATED,

    /** The item's control is inside a paragraph, table or another control, not directly in the body. */
    NESTED,

    /** The item's control can be deleted: its `sdtLocked` lock is gone. */
    UNLOCKED,

    /** A `memo-item:` control for no item of the agenda. */
    UNKNOWN,
}

/** One problem, named by its item (agenda order) or, for an unknown control, by its tag. Never an item's text. */
data class StructureProblem(
    val problem: StructureProblemKind,
    val itemId: UUID? = null,
    val number: String? = null,
    val tag: String? = null,
)

/**
 * The structure check's rule (design E06 §1.6.1, ADR-0066): every minuted item has exactly one locked, body-level content
 * control tagged `memo-item:<id>`, and no such control names anything else. Controls of other kinds (a template's own)
 * are not the minutes' business. Pure.
 */
object MinutesStructure {
    const val TAG_PREFIX = "memo-item:"
    const val ROW_PREFIX = "memo-row:"
    const val LOCK = "sdtLocked"

    fun tag(itemId: UUID): String = "$TAG_PREFIX$itemId"

    /**
     * The row-level control of a meeting-level loop's row that belongs to [itemId] (E06 T7: the resolutions table): a
     * reader's copy drops it with the item. Not an item control: the structure check does not count it.
     */
    fun rowTag(itemId: UUID): String = "$ROW_PREFIX$itemId"

    /** The problems of [controls] (a document's, in order) against [items] (agenda order): items first, then unknown tags. */
    fun problems(
        items: List<MinutedItem>,
        controls: List<FoundControl>,
    ): List<StructureProblem> {
        val ours = controls.filter { it.tag?.startsWith(TAG_PREFIX) == true }
        val byTag = ours.groupBy { checkNotNull(it.tag) }
        val perItem =
            items.mapNotNull { item ->
                val found = byTag[tag(item.id)].orEmpty()
                val body = found.filter { it.bodyLevel }
                val kind =
                    when {
                        body.isEmpty() && found.isNotEmpty() -> StructureProblemKind.NESTED
                        body.isEmpty() -> StructureProblemKind.MISSING
                        body.size > 1 -> StructureProblemKind.DUPLICATED
                        found.size > 1 -> StructureProblemKind.NESTED
                        body.single().lock != LOCK -> StructureProblemKind.UNLOCKED
                        else -> null
                    }
                kind?.let { StructureProblem(it, item.id, item.number) }
            }
        val known = items.map { tag(it.id) }.toSet()
        val unknown =
            ours.mapNotNull { it.tag }.filter { it !in known }.distinct().map {
                StructureProblem(
                    StructureProblemKind.UNKNOWN,
                    tag = it,
                )
            }
        return perItem + unknown
    }
}
