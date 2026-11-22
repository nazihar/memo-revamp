package com.memo.actions.internal

import com.memo.jooq.tables.references.ACTIONS_ITEM
import org.jooq.Condition
import org.jooq.Field
import org.jooq.impl.DSL
import java.time.LocalDate

/** The colour an action shows (design E07 §1.5): never alone, always with its [RagWord]. */
enum class RagColour { NONE, GREY, BLUE, RED, AMBER, GREEN }

/** Why an action shows its colour, in words (design E07 §1.5); each word has one colour. */
enum class RagWord(
    val colour: RagColour,
) {
    DRAFT(RagColour.NONE),
    CLOSED(RagColour.GREY),
    CANCELLED(RagColour.GREY),

    /** "Done, to be verified". */
    TO_VERIFY(RagColour.BLUE),

    /** A secretary's or the chair's override, with its reason ("set by {name}: {reason}"). */
    OVERRIDE_RED(RagColour.RED),
    OVERRIDE_AMBER(RagColour.AMBER),
    OVERRIDE_GREEN(RagColour.GREEN),
    OVERDUE(RagColour.RED),
    OFF_TRACK(RagColour.RED),

    /** "Due soon": within the committee type's `amberWorkingDays`. */
    DUE_SOON(RagColour.AMBER),
    AT_RISK(RagColour.AMBER),

    /** "No recent update": none for the committee type's `staleWorkingDays`. */
    NO_RECENT_UPDATE(RagColour.AMBER),
    ON_TRACK(RagColour.GREEN),
}

/** What the rule reads of an action: its stored columns (design E07 §1.5). Nothing else, so SQL can read the same. */
data class RagFacts(
    val status: ActionStatus,
    val dueDate: LocalDate?,
    val amberFrom: LocalDate?,
    val staleFrom: LocalDate?,
    val confidence: Confidence?,
    val override: RagColour?,
    val overrideUntil: LocalDate?,
)

/** The columns [RagRule] reads in SQL: the action's own, or a derived table's in a test. */
data class RagColumns(
    val status: Field<String?>,
    val dueDate: Field<LocalDate?>,
    val amberFrom: Field<LocalDate?>,
    val staleFrom: Field<LocalDate?>,
    val confidence: Field<String?>,
    val override: Field<String?>,
    val overrideUntil: Field<LocalDate?>,
) {
    companion object {
        val ACTION =
            RagColumns(
                ACTIONS_ITEM.STATUS,
                ACTIONS_ITEM.DUE_DATE,
                ACTIONS_ITEM.AMBER_FROM,
                ACTIONS_ITEM.STALE_FROM,
                ACTIONS_ITEM.CONFIDENCE,
                ACTIONS_ITEM.RAG_OVERRIDE,
                ACTIONS_ITEM.RAG_OVERRIDE_UNTIL,
            )
    }
}

/**
 * One test of the rule, read two ways: [holds] in Kotlin on an action's [RagFacts], and [condition] in SQL on its
 * columns. Null columns fail every test in both (SQL's unknown is false in a CASE).
 */
sealed interface RagTest {
    fun holds(
        facts: RagFacts,
        today: LocalDate,
    ): Boolean

    fun condition(
        columns: RagColumns,
        today: Field<LocalDate?>,
    ): Condition

    /** The action is in [status]. */
    data class StatusIs(
        val status: ActionStatus,
    ) : RagTest {
        override fun holds(
            facts: RagFacts,
            today: LocalDate,
        ) = facts.status == status

        override fun condition(
            columns: RagColumns,
            today: Field<LocalDate?>,
        ): Condition = columns.status.eq(DSL.inline(status.name))
    }

    /** An override of [colour] in force: until its date (that day included), or until the next update when it has none. */
    data class OverrideIs(
        val colour: RagColour,
    ) : RagTest {
        override fun holds(
            facts: RagFacts,
            today: LocalDate,
        ) = facts.override == colour && (facts.overrideUntil == null || !today.isAfter(facts.overrideUntil))

        override fun condition(
            columns: RagColumns,
            today: Field<LocalDate?>,
        ): Condition =
            columns.override
                .eq(DSL.inline(colour.name))
                .and(columns.overrideUntil.isNull.or(columns.overrideUntil.ge(today)))
    }

    /** Past the due date: the day after it, in the tenant's zone. */
    data object PastDue : RagTest {
        override fun holds(
            facts: RagFacts,
            today: LocalDate,
        ) = facts.dueDate != null && today.isAfter(facts.dueDate)

        override fun condition(
            columns: RagColumns,
            today: Field<LocalDate?>,
        ): Condition = columns.dueDate.lt(today)
    }

    /** The owner's word with their latest update. */
    data class ConfidenceIs(
        val confidence: Confidence,
    ) : RagTest {
        override fun holds(
            facts: RagFacts,
            today: LocalDate,
        ) = facts.confidence == confidence

        override fun condition(
            columns: RagColumns,
            today: Field<LocalDate?>,
        ): Condition = columns.confidence.eq(DSL.inline(confidence.name))
    }

    /** Today is on or after `amberFrom`. */
    data object DueSoon : RagTest {
        override fun holds(
            facts: RagFacts,
            today: LocalDate,
        ) = facts.amberFrom != null && !today.isBefore(facts.amberFrom)

        override fun condition(
            columns: RagColumns,
            today: Field<LocalDate?>,
        ): Condition = columns.amberFrom.le(today)
    }

    /** Today is on or after `staleFrom` (null when the committee type has no such rule). */
    data object Stale : RagTest {
        override fun holds(
            facts: RagFacts,
            today: LocalDate,
        ) = facts.staleFrom != null && !today.isBefore(facts.staleFrom)

        override fun condition(
            columns: RagColumns,
            today: Field<LocalDate?>,
        ): Condition = columns.staleFrom.le(today)
    }
}

/**
 * Red, amber and green (ADR-0067, design E07 §1.5), derived on read from the stored columns and today's date in the
 * tenant's zone, never stored: written **once**, as the ordered [CASES], and read by [word] in Kotlin (one action's page)
 * and by [field] in SQL (lists, filters, totals). `RagRuleProperties` checks that both readings agree. The first case
 * that holds gives the word; [RagWord.ON_TRACK] when none does.
 */
object RagRule {
    val CASES: List<Pair<RagWord, RagTest>> =
        listOf(
            RagWord.DRAFT to RagTest.StatusIs(ActionStatus.DRAFT),
            RagWord.CLOSED to RagTest.StatusIs(ActionStatus.CLOSED),
            RagWord.CANCELLED to RagTest.StatusIs(ActionStatus.CANCELLED),
            RagWord.TO_VERIFY to RagTest.StatusIs(ActionStatus.DONE_PENDING),
            RagWord.OVERRIDE_RED to RagTest.OverrideIs(RagColour.RED),
            RagWord.OVERRIDE_AMBER to RagTest.OverrideIs(RagColour.AMBER),
            RagWord.OVERRIDE_GREEN to RagTest.OverrideIs(RagColour.GREEN),
            RagWord.OVERDUE to RagTest.PastDue,
            RagWord.OFF_TRACK to RagTest.ConfidenceIs(Confidence.OFF_TRACK),
            RagWord.DUE_SOON to RagTest.DueSoon,
            RagWord.AT_RISK to RagTest.ConfidenceIs(Confidence.AT_RISK),
            RagWord.NO_RECENT_UPDATE to RagTest.Stale,
        )

    /** The colours an override may set. */
    val OVERRIDES = setOf(RagColour.RED, RagColour.AMBER, RagColour.GREEN)

    /** The rule in Kotlin: the word of [facts] [today]. */
    fun word(
        facts: RagFacts,
        today: LocalDate,
    ): RagWord = CASES.firstOrNull { (_, test) -> test.holds(facts, today) }?.first ?: RagWord.ON_TRACK

    /** The rule in SQL: the word's name, for [columns] and [today]. */
    fun field(
        today: Field<LocalDate?>,
        columns: RagColumns = RagColumns.ACTION,
    ): Field<String> {
        val (first, rest) = CASES.first() to CASES.drop(1)
        var case = DSL.`when`(first.second.condition(columns, today), DSL.inline(first.first.name))
        rest.forEach { (word, test) -> case = case.`when`(test.condition(columns, today), DSL.inline(word.name)) }
        return case.otherwise(DSL.inline(RagWord.ON_TRACK.name))
    }

    /** [field] for today's date [today]. */
    fun field(today: LocalDate): Field<String> = field(DSL.value(today))

    /** The actions showing [colour] [today], from the same rule (the tracker's filter and totals, T8). */
    fun shows(
        colour: RagColour,
        today: Field<LocalDate?>,
        columns: RagColumns = RagColumns.ACTION,
    ): Condition = field(today, columns).`in`(RagWord.entries.filter { it.colour == colour }.map { DSL.inline(it.name) })
}

/** How the owner says it is going, with an update (design E07 §1.6). */
enum class Confidence { ON_TRACK, AT_RISK, OFF_TRACK }
