package com.memo.minutes.internal

import com.memo.templating.FoundControl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The structure check's rule (design E06 §1.6.1, ADR-0066): exactly one locked, body-level control per minuted item; a
 * missing, duplicated, nested (inline) or unlocked one is named by its item, an unknown `memo-item:` tag by its tag, and
 * controls of other kinds (a template's own) are left alone.
 */
class MinutesStructureTest {
    private val items = (1..4).map { MinutedItem(UUID.randomUUID(), it.toString()) }

    private fun control(
        item: MinutedItem,
        lock: String? = LOCKED,
        bodyLevel: Boolean = true,
    ) = FoundControl(MinutesStructure.tag(item.id), lock, "Item ${item.number}", bodyLevel)

    @Test
    fun `one locked control per item is a sound draft`() {
        assertThat(MinutesStructure.problems(items, items.map { control(it) })).isEmpty()
        // A template's own controls (a date picker, a cover page) are not the minutes' business.
        val own = FoundControl("cover", null, "Cover", bodyLevel = true)
        assertThat(MinutesStructure.problems(items, listOf(own) + items.map { control(it) })).isEmpty()
    }

    @Test
    fun `each problem names its item, in agenda order`() {
        val one = items[0]
        val two = items[1]
        val three = items[2]
        val four = items[3]
        val stray = UUID.randomUUID()
        val found =
            listOf(
                control(one, lock = null),
                control(three),
                control(three),
                control(four, bodyLevel = false),
                FoundControl("memo-item:$stray", LOCKED, "Item 9", bodyLevel = true),
                FoundControl("memo-item:not-an-id", LOCKED, null, bodyLevel = true),
            )
        val problems = MinutesStructure.problems(items, found)
        assertThat(problems.map { it.problem to it.number }).containsExactly(
            StructureProblemKind.UNLOCKED to "1",
            StructureProblemKind.MISSING to "2",
            StructureProblemKind.DUPLICATED to "3",
            StructureProblemKind.NESTED to "4",
            StructureProblemKind.UNKNOWN to null,
            StructureProblemKind.UNKNOWN to null,
        )
        assertThat(problems.take(4).map { it.itemId }).containsExactly(one.id, two.id, three.id, four.id)
        assertThat(problems[4].itemId).isNull()
        assertThat(problems.drop(4).map { it.tag }).containsExactly("memo-item:$stray", "memo-item:not-an-id")
    }

    @Test
    fun `an empty document misses every item`() {
        assertThat(MinutesStructure.problems(items, emptyList()).map { it.problem }.distinct())
            .containsExactly(StructureProblemKind.MISSING)
        assertThat(MinutesStructure.problems(emptyList(), emptyList())).isEmpty()
    }

    private companion object {
        const val LOCKED = "sdtLocked"
    }
}
